/*
 *  FeatureCorrelation.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki
package impl

import xml.XML
import java.io.File
import collection.breakOut
import scala.IndexedSeq
import scala.Some
import de.sciss.synth.io.AudioFile
import collection.immutable.{SortedSet => ISortedSet}
import concurrent.{ExecutionContext, Promise, blocking}

private[strugatzki] final class FeatureCorrelation(val config: FeatureCorrelation.Config,
                                                   protected val observer: FeatureCorrelation.Observer,
                                                   protected val promise: Promise[FeatureCorrelation.PayLoad])
                                                  (implicit protected val executionContext: ExecutionContext)
  extends ProcessorImpl[FeatureCorrelation.PayLoad, FeatureCorrelation.Config] {

  import FeatureCorrelation._

   protected val companion = FeatureCorrelation

  protected def body(): PayLoad = blocking {
    import FeatureExtraction.{Config => ExtrSettings}

    val extrIn    = ExtrSettings.fromXML(XML.loadFile(config.metaInput))
    val stepSize  = extrIn.fftSize / extrIn.fftOverlap

    def fullToFeat(n: Long) = ((n + (stepSize >> 1)) / stepSize).toInt
    def featToFull(i: Int)  = i.toLong * stepSize

    // collect all valid database files from the folder
    val punchMetas = config.databaseFolder.children(_.name.endsWith("_feat.xml")).toSet - config.metaInput

    if (verbose) {
      println("Number of files in database : " + punchMetas.size)
    }

    // collect all database entries which match the input resolution
    // (for simplicity, we ignore the fact that the sample rates could differ)
    val extrDBs: IndexedSeq[ExtrSettings] = punchMetas.map(file => {
      val e = ExtrSettings.fromXMLFile(file)
      if ((e.numCoeffs == extrIn.numCoeffs) && (e.fftSize / e.fftOverlap == stepSize)) Some(e) else None
    })(breakOut).collect {
      case Some(e) => e
    }

    if (verbose) {
      println("Number of compatible files in database : " + extrDBs.size)
    }

    val normBuf = if (config.normalize) {
      val afNorm = AudioFile.openRead(new File(config.databaseFolder, Strugatzki.NORMALIZE_NAME))
      try {
        require((afNorm.numChannels == extrIn.numCoeffs + 1) && afNorm.numFrames == 2L)
        val b = afNorm.buffer(2)
        afNorm.read(b)
        b
      } finally {
        afNorm.close()
      }
    } else null

    def calcLnAvgLoud(b: Array[Float], bOff: Int, bLen: Int) = math.log(MathUtil.avg(b, bOff, bLen))

    def calcBoost(in: InputMatrix, b: Array[Float]): Float = {
      val lnAvgB = calcLnAvgLoud(b, 0, in.numFrames)
      math.exp((in.lnAvgLoudness - lnAvgB) / 0.6).toFloat
    }

    val (matrixIn, matrixOutO) = {
      val afIn = AudioFile.openRead(extrIn.featureOutput)
      try {
        def readInBuffer(punch: Punch): InputMatrix = {
          val start = fullToFeat(punch.span.start)
          val stop = fullToFeat(punch.span.stop)
          val frameNum = stop - start
          val b = afIn.buffer(frameNum)
          afIn.seek(start)
          afIn.read(b)
          MathUtil.normalize(normBuf, b, 0, frameNum)

          def feat(mat: Array[Array[Float]]) = {
            val (mean, stdDev) = MathUtil.stat(mat, 0, frameNum, 0, mat.length)
            FeatureMatrix(mat, frameNum, mean, stdDev)
          }

          InputMatrix(feat(b.take(1)), feat(b.drop(1)), calcLnAvgLoud(b(0), 0, frameNum))
        }

        // Outline of Algorithm:
        // - read input feature in-span and out-span
        // - optionally normalize
        (readInBuffer(config.punchIn), config.punchOut.map(readInBuffer(_)))
      } finally {
        afIn.close()
      }
    }

    val punchInLen = matrixIn.numFrames
    val punchOutLen = matrixOutO.map(_.numFrames).getOrElse(0)
    val inTempWeight = config.punchIn.temporalWeight

    var allPrio   = ISortedSet.empty[Match](MatchMinOrd)
    var entryPrio = ISortedSet.empty[Match](MatchMinOrd)
    var lastEntryMatch: Match = null

    val minPunch = fullToFeat(config.minPunch)
    val maxPunch = fullToFeat(config.maxPunch)

    def entryHasSpace = {
      val maxEntrySz = math.min(config.numMatches - allPrio.size, config.numPerFile)
      entryPrio.size < maxEntrySz
    }

    def lowestSim = {
      if (entryPrio.nonEmpty) entryPrio.last.sim
      else if (allPrio.nonEmpty) allPrio.last.sim
      else 0f // Float.NegativeInfinity
    }

    // adds a match to the entry's priority queue. if the queue grows beyond numPerFile,
    // truncates the queue. if the match collides with a previous match that is closer
    // than minSpacing, it is either dropped (if the similarity is equal or smaller) or replaces
    // the previous match (if the similarity is greater).
    def addMatch(m: Match) {
      if ((lastEntryMatch != null) && (m.punch.spacing(lastEntryMatch.punch) < config.minSpacing)) {
        // gotta collapse them
        if (lastEntryMatch.sim < m.sim) {
          // ok, replace previous match
          entryPrio -= lastEntryMatch
          entryPrio += m
          lastEntryMatch = m
        } // otherwise ignore the new match
      } else {
        entryPrio += m
        if (entryPrio.size > config.numPerFile) {
          entryPrio -= entryPrio.last // faster than dropRight( 1 ) ?
        }
        lastEntryMatch = m
      }
    }

    val tInBuf   = Array.ofDim[Float](2, 1024)
    val tOutBuf = Array.ofDim[Float](2, 1024) // tOut.frameBuffer( 1024 )
    val eInBuf = Array.ofDim[Float](extrIn.numCoeffs + 1, punchInLen)
    val eOutBuf = Array.ofDim[Float](extrIn.numCoeffs + 1, punchOutLen)
    var tIn: AudioFile = null
    var tOut: AudioFile = null

    try {
      // - for each span:
      extrDBs.zipWithIndex foreach {
        case (extrDB, extrIdx) =>

          checkAborted()

          if (entryPrio.nonEmpty) entryPrio = entryPrio.empty
          lastEntryMatch = null

          val afExtr = AudioFile.openRead(extrDB.featureOutput)
          try {
            //   - create a temp file
            //   - write the sliding xcorr to that file
            // A simple optimization could be to not begin writing the
            // temp file unless a punch-in correlation is found which is better
            // than the previous best match. This could also trigger
            // the punch-out measurement which could thus offset at
            // first_punch_in + min_punch_len
            var tInOpen = false
            var tInOff = 0
            var tInBufOff = 0
            //  val b          = afExtr.frameBuffer( math.max( punchInLen, punchOutLen ))
            var left = afExtr.numFrames
            matrixOutO.foreach {
              mo => left -= minPunch /* + mo.numFrames */
            }
            var readSz = punchInLen // read full buffer in first round
            var readOff = 0
            var logicalOff = 0
            // - go through in-span file and calculate correlations
            while (left > 0) {

              checkAborted()

              val chunkLen = math.min(left, readSz).toInt
              afExtr.read(eInBuf, readOff, chunkLen)
              val eInBufOff = logicalOff % punchInLen
              MathUtil.normalize(normBuf, eInBuf, readOff, chunkLen)
              val boost = calcBoost(matrixIn, eInBuf(0))
              val sim = if (boost <= config.maxBoost) {
                val temporal = if (inTempWeight > 0f) {
                  correlate(matrixIn.temporal, eInBuf, eInBufOff, 0)
                } else 0f
                val spectral = if (inTempWeight < 1f) {
                  correlate(matrixIn.spectral, eInBuf, eInBufOff, 1)
                  //if( res > 1 ) println( "spec : " + res + " " + logicalOff )
                } else 0f
                temporal * inTempWeight + spectral * (1f - inTempWeight)
              } else {
                0f // Float.NegativeInfinity
              }

              if (matrixOutO.isDefined) {
                if (tInOpen || entryHasSpace || sim > lowestSim) {
                  if (!tInOpen) {
                    if (tIn == null) {
                      tIn = IOUtil.createTempAudioFile("in", 2)
                    } else {
                      tIn.seek(0L)
                    }
                    tInOff = logicalOff
                    tInOpen = true
                  }
                  tInBuf(0)(tInBufOff) = sim
                  tInBuf(1)(tInBufOff) = boost
                  tInBufOff += 1
                  // flush
                  if (tInBufOff == 1024) {
                    tIn.write(tInBuf, 0, tInBufOff)
                    tInBufOff = 0
                  }
                }
              } else {
                if (entryHasSpace || sim > lowestSim) {
                  val start = featToFull(logicalOff)
                  val stop = featToFull(logicalOff + punchInLen)
                  val m = Match(sim, extrDB.audioInput, Span(start, stop), boost, 1f)
                  addMatch(m)
                }
              }

              left -= chunkLen
              readOff = (readOff + chunkLen) % punchInLen
              logicalOff += 1
              readSz = 1 // read single frames in successive round (and rotate buffer)
            }

            // - if there is no punch-out, or if no minimally good correlations have been found,
            //   we're done, otherwise, calculate punch-out correlations
            (matrixOutO, config.punchOut, tInOpen) match {
              case (Some(matrixOut), Some(punchOut), true) =>
                // flush
                if (tInBufOff > 0) {
                  tIn.write(tInBuf, 0, tInBufOff)
                  tInBufOff = 0
                }

                tIn.seek(0L)

                val poOff0 = tInOff + minPunch

                left = afExtr.numFrames - (poOff0 /*+ matrixOut.numFrames */)
                if (left >= matrixOut.numFrames) {
                  // means we actually do at least one full correlation
                  if (tOut == null) {
                    tOut = IOUtil.createTempAudioFile("out", 2)
                  } else {
                    tOut.seek(0L)
                  }

                  val outTempWeight = punchOut.temporalWeight
                  afExtr.seek(poOff0)
                  readSz = punchOutLen // read full buffer in first round
                  readOff = 0
                  logicalOff = 0
                  // - go through out-span file and calculate correlations

                  var tOutBufOff = 0
                  val tOutSize = left
                  while (left > 0) {

                    checkAborted()

                    val chunkLen = math.min(left, readSz).toInt
                    afExtr.read(eOutBuf, readOff, chunkLen)
                    MathUtil.normalize(normBuf, eOutBuf, readOff, chunkLen)
                    val extraBufOff = logicalOff % punchOutLen
                    val boost = calcBoost(matrixOut, eOutBuf(0))
                    val sim = if (boost <= config.maxBoost) {
                      val temporal = if (outTempWeight > 0f) {
                        correlate(matrixOut.temporal, eOutBuf, extraBufOff, 0)
                      } else 0f
                      val spectral = if (outTempWeight < 1f) {
                        correlate(matrixOut.spectral, eOutBuf, extraBufOff, 1)
                      } else 0f
                      temporal * outTempWeight + spectral * (1f - outTempWeight)
                    } else {
                      0f // Float.NegativeInfinity
                    }

                    tOutBuf(0)(tOutBufOff) = sim
                    tOutBuf(1)(tOutBufOff) = boost
                    tOutBufOff += 1
                    if (tOutBufOff == 1024) {
                      // flush
                      tOut.write(tOutBuf, 0, tOutBufOff)
                      tOutBufOff = 0
                    }

                    left -= chunkLen
                    readOff = (readOff + chunkLen) % punchOutLen
                    logicalOff += 1
                    readSz = 1 // read single frames in successive round (and rotate buffer)
                  }
                  // flush
                  if (tOutBufOff > 0) {
                    tOut.write(tOutBuf, 0, tOutBufOff)
                    tOutBufOff = 0
                  }

                  left      = afExtr.numFrames - poOff0
                  tInBufOff = 1024
                  var piOff = tInOff
                  while (left > 0) {

                    checkAborted()

                    if (tInBufOff == 1024) {
                      tIn.read(tInBuf, 0, math.min(1024, left).toInt)
                      tInBufOff = 0
                    }

                    val inSim   = tInBuf(0)(tInBufOff)
                    val boostIn = tInBuf(1)(tInBufOff)

                    //                        val piOff   = tIn.readInt()
                    //                        val inSim   = tIn.readFloat()
                    //                        val boostIn = tIn.readFloat()
                    // lowestSim is now
                    // defined as min( inSim, outSim )
                    var low = lowestSim // cache it here
                    var hs = entryHasSpace // cahce it here
                    //                        if( hs || inSim > ws ) // for sim = min( inSim, outSim )
                    if (inSim > (low * low)) {
                      // sqrt( inSim * 1 ) > ws
                      var poOff = piOff + minPunch
                      // note: there is room for further optimization:
                      // we might track in this iteration the best sim
                      // in tOut, and in the next iteration, if this
                      // best sim is too bad -- we can just skip over
                      // the whole previous search span!
                      val tOutSeek = piOff - tInOff // = numRead from tIn !
                      tOut.seek(tOutSeek)

                      //                           tOut.seek( (poOff0 + (piOff - piOff0)) * 8 )
                      //                           var left2   = math.min( (tOut.length - tOut.getFilePointer) / 8, maxPunch - minPunch + 1 ) // float <sim>, float <boost>
                      var left2 = math.min(tOutSize - tOutSeek, maxPunch - minPunch + 1)
                      //println( "---2 (" + left2 + ") " + hs + " | " + ws )
                      while (left2 > 0) {

                        checkAborted()

                        val chunkLen = math.min(left2, 1024).toInt
                        tOut.read(tOutBuf, 0, chunkLen)

                        var chunkOff = 0
                        while (chunkOff < chunkLen) {
                          val outSim    = tOutBuf(0)(chunkOff)
                          val boostOut  = tOutBuf(1)(chunkOff)

                          // ok, let's try geometric mean, meaning that
                          // in the case of inSim < outSim, the result
                          // could still be differentiated among several
                          // outSim! (which would be lost in the case of min( inSim, outSim )
                          val sim = math.sqrt(inSim * outSim).toFloat
                          //if( sim > 1 ) println( "inSim = " + inSim + " outSim = " + outSim + " sim = " + sim )
                          if (hs || sim > low) {
                            val m = Match(sim, extrDB.audioInput,
                              Span(featToFull(piOff), featToFull(poOff)), boostIn, boostOut)
                            addMatch(m)
                            // clear cache
                            low = lowestSim
                            hs  = entryHasSpace
                          }
                          chunkOff += 1
                          poOff    += 1
                        }
                        left2 -= chunkLen // 1
                      }
                    }
                    left      -= 1
                    tInBufOff += 1
                    piOff     += 1
                  }
                }

              case _ =>
            }
          } finally {
            afExtr.close()
          }

          // - add iter-prio to total-prio, and truncate after num-matches elements
          allPrio ++= entryPrio
          if (allPrio.size > config.numMatches) allPrio = allPrio.take(config.numMatches)

          progress((extrIdx + 1).toFloat / extrDBs.size)
      }

    } finally {
      if (tIn != null) tIn.close()
      if (tOut != null) tOut.close()
    }

    val pay = allPrio.toIndexedSeq
    pay
  }

  private def correlate(a: FeatureMatrix, b: Array[Array[Float]], bFrameOff: Int, bChanOff: Int): Float = {
    val numChannels = a.numChannels
    val numFrames   = a.numFrames
    // note: stat does not wrap frame offset around b.numFrames.
    // we thus assume that b really has data from 0 to a.numFrames!
    val (bMean, bStdDev) = MathUtil.stat(b, 0 /* FrameOff */ , numFrames, bChanOff, numChannels)
    MathUtil.correlate(a.mat, a.mean, a.stdDev, numFrames, numChannels, b, bMean, bStdDev, bFrameOff, bChanOff)
  }
}