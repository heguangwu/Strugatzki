/*
 *  ProcSehen.scala
 *  (InterPlay)
 *
 *  Copyright (c) 2011 Hanns Holger Rutz. All rights reserved.
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
 *
 *
 *  Changelog:
 */

package de.sciss.utopia

import de.sciss.synth._
import io.{SampleFormat, AudioFileType, AudioFile, AudioFileSpec}
import proc._
import ugen._
import java.io.File
import java.util.TimerTask
import de.sciss.fscape.FScapeJobs
import collection.immutable.{SortedSet => ISortedSet}
import AnalysisBuffer._
import actors.Actor

/**
 * Picks up the current spectral and temporal pattern from the sum signal,
 * and measures the similarity in the live buffer. Uses FScape's DrMurke
 * to sort out similar sounds which are the re-injected.
 */
object ProcSehen {
//   val anaFrames  = 10
   val verbose    = true
   val THRESH     = 0.5
   val HYST       = 0.6

   val folder     = new File( "/Users/hhrutz/Desktop/Utopia/audio_work" )

   private val actor = new Actor { def act = loop { react {
      case d: Do => try {
         d.perform
      } catch {
         case e =>
            println( "Caught exception in actor:" )
            e.printStackTrace()
      }
   }}}

   def main( args: Array[ String ]) {
      System.setProperty( "actors.enableForkJoin", "false" )
      actor.start
      FScape.fsc.connect() { succ =>
         println( "FScape connect : " + succ )
         if( !succ ) System.exit( 1 )
         Server.test { s =>
            ProcDemiurg.addServer( s )
//            processTemplate( "StalkerPt1Ed.aif", "StalkerTemplate2.aif" )
            prepareFull( "StalkerPt1Ed.aif" )
         }
      }
   }

   def processTemplate( inName: String, tempName: String ) {
      val inPath     = new File( folder, inName ).getAbsolutePath // "StalkerPt1Ed.aif"
      val anaPath    = {
         val i = inPath.lastIndexOf( '.' )
         inPath.substring( 0, i ) + "_Ana.aif"
      }
      val tempPath   = new File( folder, tempName ).getAbsolutePath // "/Users/hhrutz/Desktop/Utopia/audio_work/StalkerTemplate2.aif"
      val (ctrlPath, outPath) = {
         val i = inPath.lastIndexOf( '.' )
         (inPath.substring( 0, i ) + "_Ctrl.aif", inPath.substring( 0, i ) + "_Sim.aif")
      }

      val afAna      = AudioFile.openRead( anaPath )
      val matNumFr   = (afAna.numFrames - 2).toInt
      val buf        = afAna.frameBuffer( 1 )
      afAna.readFrames( buf )
      val mins       = Array.tabulate( afAna.numChannels )( buf( _ )( 0 ))
      afAna.readFrames( buf )
      val maxs       = Array.tabulate( afAna.numChannels )( buf( _ )( 0 ))
      val mat1       = Similarity.Mat( matNumFr, afAna.numChannels )
      var x = 0; while( x < mat1.numFrames ) {
         afAna.readFrames( buf )
         var y = 0; while( y < mat1.numChannels ) {
            mat1.arr( x )( y ) = buf( y )( 0 )
         y += 1 }
      x += 1 }
      afAna.close

      spawnAtomic( "ana1" ) { implicit tx =>
         process( tempPath, norm = false ) { mat2 =>
            norm( mat2, mins, maxs )
            spawnAtomic( "ana-done" ) { implicit tx => actProcessAna( mat1, mat2, ctrlPath, inPath, outPath )}
         }
      }
   }

   def prepareFull( name: String ) {
      val inPath     = new File( folder, name ).getAbsolutePath // "/Users/hhrutz/Desktop/Utopia/audio_work/rec110211_190439-1Cut3_41_894Rot.aif"
      val outPath    = {
         val i = inPath.lastIndexOf( '.' )
         inPath.substring( 0, i ) + "_Ana.aif" // "/Users/hhrutz/Desktop/Utopia/audio_work/StalkerPt1Ed_Ana.aif"
      }
//      val statPath   = "/Users/hhrutz/Desktop/Utopia/audio_work/StalkerPt1Ed_Stat.aif"

      spawnAtomic( "ana1" ) { implicit tx =>
         process( inPath, norm = false  ) { mat1 =>

            println( "Done. Stats..." )
            val (mins, maxs) = stat( mat1 )
//            val afStat = AudioFile.openWrite( statPath, AudioFileSpec( AudioFileType.AIFF, SampleFormat.Float, mins.size, 44100.0 ))
//            println( "Done. Write Stats..." )
//            afStat.writeFrames( Array( mins ))
//            afStat.writeFrames( Array( maxs ))
//            afStat.close
            println( "Done. Norm..." )
            norm( mat1, mins, maxs )
            println( "Done. Write Ana..." )
            val afOut = AudioFile.openWrite( outPath, AudioFileSpec( AudioFileType.AIFF, SampleFormat.Float, mat1.numChannels, 44100.0 / anaWinStep ))
            afOut.writeFrames( mins.map( f => Array( f )))
            afOut.writeFrames( maxs.map( f => Array( f )))
            val buf = afOut.frameBuffer( 1 )
            var x = 0; while( x < mat1.numFrames ) {
               var y = 0; while( y < mat1.numChannels ) {
                  buf( y )( 0 ) = mat1.arr( x )( y )
               y += 1 }
               afOut.writeFrames( buf )
            x += 1 }
            afOut.close
            println( "Done." )
         }
      }
   }

   private def norm( mat: Similarity.Mat, mins: Array[ Float ], maxs: Array[ Float ]) {
      require( mins.zip(maxs).forall( tup => tup._2 > tup._1 ))

      var x = 0; while( x < mat.numFrames ) {
         val frame = mat.arr( x )
         var y = 0; while( y < mat.numChannels ) {
            frame( y ) = (frame( y ) - mins( y )) / (maxs( y ) - mins( y ))
         y += 1 }
      x += 1 }
   }

   private def stat( mat: Similarity.Mat ) : (Array[ Float ], Array[ Float ]) = {
      val mins = Array.fill[ Float ]( mat.numChannels )( Float.PositiveInfinity )
      val maxs = Array.fill[ Float ]( mat.numChannels )( Float.NegativeInfinity )

      var x = 0; while( x < mat.numFrames ) {
         val frame = mat.arr( x )
         var y = 0; while( y < mat.numChannels ) {
            mins( y ) = math.min( mins( y ), frame( y ))
            maxs( y ) = math.max( maxs( y ), frame( y ))
         y += 1 }
      x += 1 }
      (mins, maxs)
   }



   def process( path: String, speed: Double = 1.0, norm: Boolean = true )( doneFun: Similarity.Mat => Unit )(  implicit tx: ProcTxn ) {
      import DSL._
      val d = (gen( "ana" ) {
         graph {
            val spec       = audioFileSpec( path )
//            val speed      = 0.5 // 1.0 // pspeed.ar
            val sig        = VDiskIn.ar( spec.numChannels, bufCue( path ).id, speed )

            val chain      = FFT( bufEmpty( anaFFTSize ).id, Mix( sig ), anaFFTOver.reciprocal )
            val coeffs     = MFCC.kr( chain, numMelCoeffs )
            val fftTrig    = Impulse.kr( SampleRate.ir / anaWinStep ) & (Mix( coeffs ) > 0)
            val fftCnt     = PulseCount.kr( fftTrig )
            val me         = Proc.local
//            val anaFrames  = (TEND_ANA_DUR.decide * SAMPLE_RATE / anaWinStep + 0.5).toInt
            val anaFrames  = (spec.numFrames / (speed * anaWinStep) + 0.5).toInt
            val anaBuf     = Similarity.Mat( anaFrames, anaChans )
var percDone = 0
            fftTrig.react( fftCnt +: coeffs.outputs ) { data =>
               val iter    = data.iterator
               val cnt     = iter.next.toInt - 1
               if( cnt < anaFrames ) {
                  val frame = anaBuf.arr( cnt )
                  var i = 0; while( i < numMelCoeffs ) {
                     val f1 = iter.next.toFloat
                     frame( i ) = if( !norm ) f1 else (f1 + normAdd( i )) * normMul( i )
                  i += 1 }
                  val p = ((cnt + 1) * 20) / anaFrames
                  while( percDone < p ) {
                     print( "#" )
                     percDone += 1
                  }
               } else {
                  spawnAtomic( "ana removal" ) { implicit tx =>
//                     ProcessHelper.stopAndDispose( me )
                     println()
                     me.stop
                     me.dispose
                     doneFun( anaBuf )
                  }
               }
            }
            sig // 0.0
         }
      }).make

      d.play
   }

   private def blockWithTimeOut[ Z ]( info: => String, block: ProcTxn => Z, tx: ProcTxn ) : Z = {
      val timeOut = new java.util.Timer( true )
      timeOut.schedule( new TimerTask {
         def run {
            informDir( "Timeout for " + info, force = true )
         }
      }, 4000L )
      try {
         block( tx )
      } finally {
         timeOut.cancel()
      }
   }

   def afterCommit( tx: ProcTxn )( thunk: => Unit ) {
      require( tx.isActive, "Juhuuu. tx not active anymore" )
      tx.afterCommit( _ => thunk )
   }

   def timeString() = (new java.util.Date()).toString

   private def printWithTime( what: String ) {
      println( timeString() + " " + what )
   }

   private def doInform( what: => String )( implicit tx: ProcTxn ) {
      if( tx.isActive ) tx.afterCommit( _ => printWithTime( what ))
      else printWithTime( what )
   }

   private def inform( what: => String, force: Boolean = false )( implicit tx: ProcTxn ) = if( verbose || force ) {
      doInform( "Process : " + what )
   }

   private def informDir( what: => String, force: Boolean = false ) = if( verbose || force ) {
       printWithTime( what )
   }

   def atomic[ Z ]( info: => String )( block: ProcTxn => Z ) : Z = {
      ProcTxn.atomic { tx =>
         blockWithTimeOut( info, block, tx )
      }
   }

   def spawnAtomic[ Z ]( info: => String )( block: ProcTxn => Z ) {
      ProcTxn.spawnAtomic { tx =>
         blockWithTimeOut( info, block, tx )
      }
   }

//   private def processAnalysis( mat: Similarity.Mat )( implicit tx: ProcTxn ) {
//      afterCommit( tx )( actProcessAna( mat ))
//   }

   private def actProcessAna( anaClientBuf: Similarity.Mat, mat: Similarity.Mat, ctrlPath: String, inPath: String, outPath: String ) {
      try {
//         val f             = File.createTempFile( "tmp", ".aif" )
         val f             = new File( ctrlPath )
         val spec          = AudioFileSpec( numChannels = 1, sampleRate = 44100.0 / anaWinStep )
         val afCtrl        = AudioFile.openWrite( f, spec )
         val afBuf         = afCtrl.frameBuffer( 1024 )
         val afChan        = afBuf( 0 )
         var pos           = 0
         val numAnaFrames  = anaClientBuf.numFrames // availableLiveRecordingFrames
         informDir( "processAnalysis " + numAnaFrames )
         if( numAnaFrames == 0 ) return

         def flush {
            afCtrl.writeFrames( afBuf, 0, pos )
            pos = 0
         }

         var sum          = 0.0
         def processMeasure( dstMat: Similarity.Mat ) : Float = {
            val m = Similarity.xcorr( mat )( dstMat )
            if( pos < numAnaFrames ) {
               afChan( pos ) = m
               pos += 1
               if( pos == 1024 ) flush
            }
            sum += m
            m
         }

         def truncDone {
            informDir( "ready for murke" )
            val mean       = sum / numAnaFrames
            val upThresh   = THRESH // TEND_THRESH.decide * mean
            val downThresh = upThresh * HYST // TEND_HYST.decide
            val ctrlPath   = afCtrl.file.get.getAbsolutePath()
//            val outPath    = File.createTempFile( "fsc", ".aif" ).getAbsolutePath()
            val doc = FScapeJobs.DrMurke(
               inPath, ctrlPath, outPath, FScapeJobs.OutputSpec.aiffInt, FScapeJobs.Gain.normalized,
               mode = "up", threshUp = upThresh.toString, threshDown = downThresh.toString,
               durUp = "0.1s", durDown = "0.1s", attack = "0.01s", release = "1.0s", spacing = Some( "0s" ))
            FScape.fsc.process( "murke", doc ) { success =>
               informDir( "murke done " + success )
               // atomic can lead to timeout here...
               if( success ) spawnAtomic( " fscape done" ) { implicit tx =>
//                     stopThinking
//                     startPlaying
//                     inject( outPath )
                  doneAll( outPath )
                  //                     reentry
               } else {
                  informDir( "FScape failure!", force = true )
                  System.exit( 1 )
               }
            }
         }

         def measureDone {
            flush
            afCtrl.close
            informDir( "getting trunc file" )
//            atomic( " measure done" )( implicit tx => truncateLiveRecording( numAnaFrames )( truncDone( _ )))
            truncDone
         }

         // grmpfff
         atomic( " start searchAnalysisM" )( implicit tx => searchAnalysisM( anaClientBuf, mat.numFrames,
                          maxResults = 1, // hmmm...
                          measure = processMeasure( _ ))( _ => measureDone ))
      } catch {
         case e =>
            informDir( "Error in process-analysis:", force = true )
            e.printStackTrace()
//            atomic( fastReentry( _ ))
      }
   }

   def searchAnalysisM( anaClientBuf: Similarity.Mat, frameInteg: Int, maxResults: Int = 20, measure: Similarity.Mat => Float )
                      ( fun: ISortedSet[ Sample ] => Unit, rotateBuf: Boolean = false )( implicit tx: ProcTxn ) {
      require( maxResults > 0, "maxResults must be > 0, but is " + maxResults )
      spawn( actSearchAnaM( anaClientBuf, frameInteg, maxResults, measure )( fun ))
   }

   private def actSearchAnaM( anaClientBuf: Similarity.Mat, frameInteg: Int, maxResults: Int = 20, measure: Similarity.Mat => Float )
                            ( fun: ISortedSet[ Sample ] => Unit, rotateBuf: Boolean = false ) {
      informDir( "searchAnalysisM started" )
      val buf        = anaClientBuf
      val numChannels= buf.numChannels
//         val frames     = Array.ofDim[ Float ]( frameInteg, numChannels )
      val frames     = Similarity.Mat( frameInteg, numChannels )
//      val numFrames  = buf.framesWritten - frameInteg + 1
      val numFrames  = buf.numFrames - frameInteg + 1
      var res        = ISortedSet.empty[ Sample ]( sampleOrd )
      var resCnt     = 0
      val frameIntegM= frameInteg - 1

      def karlheinz( idx: Int ) {
         val m = measure( frames )
         if( resCnt < maxResults ) {
            res += Sample( idx, m )
            resCnt += 1
         } else if( res.last.measure > m ) {
            res = res.dropRight( 1 ) + Sample( idx, m )
         }
      }

      if( numFrames > 0 ) {
         var x = 0; while( x < frameInteg ) {
            buf.getFrame( 0, frames.arr( x ))
         x += 1 }
         karlheinz( 0 )
      }
      var off = 1; while( off < numFrames ) {
//            val fm = frameMeasure( buf.getFrame( off, chanBuf ))
         if( rotateBuf ) {
            var y = 0; while( y < numChannels ) {
               var prev = frames.arr( 0 )( y )
               var x = frameIntegM; while( x >= 0 ) {   // ouch....
                  val tmp = frames.arr( x )( y )
                  frames.arr( x )( y ) = prev
                  prev = tmp
               x -= 1 }
            y += 1 }
            buf.getFrame( off, frames.arr( frameIntegM ))
         } else {
            buf.getFrame( off, frames.arr( (off - 1) % frameInteg ))
         }
         karlheinz( off )
      off += 1 }

      informDir( "searchAnalysisM done" )
      fun( res )
   }

   private def spawn( thunk: => Unit )( implicit tx: ProcTxn ) = afterCommit( tx ) { actor ! Do( thunk )}

   def doneAll( outPath: String )( implicit tx: ProcTxn ) {
      informDir( "DONE! " + outPath )
      System.exit( 0 )
   }

   private object Do { def apply( thunk: => Unit ) = new Do( thunk )}
   private class Do( thunk: => Unit ) { def perform = thunk }
   case class Sample( idx: Int, measure: Float ) extends Ordered[ Sample ] {
       def compare( that: Sample ) : Int = idx.compare( that.idx )
   }
   private val sampleOrd = Ordering.ordered[ Sample ]
}