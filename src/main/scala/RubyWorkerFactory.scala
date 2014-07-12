package org.apache.spark.api.ruby

import java.io.{DataInputStream, InputStream, OutputStreamWriter}
import java.net.{InetAddress, ServerSocket, Socket, SocketException}

import scala.collection.JavaConversions._

import org.apache.spark._
import org.apache.spark.util.Utils

import org.apache.spark.api.python.RedirectThread

class RubyWorkerFactory(workerDir: String) extends Logging {
  
  val PROCESS_WAIT_TIMEOUT_MS = 10000

  // def create(): Socket = {
  //   var serverSocket: ServerSocket = null
  //   try {
  //     serverSocket = new ServerSocket(0, 1, InetAddress.getByAddress(Array(127, 0, 0, 1)))

  //     // val execCommand = "ruby"
  //     // val execOptions = ""
  //     // val execScript  = "/vagrant_data/lib/spark/worker.rb"

  //     // val args = List(execCommand, execOptions, execScript)

  //     val pb = new ProcessBuilder(workerPath)
  //     // val pb = new ProcessBuilder(args: _*)
  //     // val pb = new ProcessBuilder(execCommand, execOptions, execScript)
  //     // pb.environment().put("", "")
  //     val worker = pb.start()

  //     // Redirect worker stdout and stderr
  //     redirectStreamsToStderr(worker.getInputStream, worker.getErrorStream)

  //     // Tell the worker our port
  //     val out = new OutputStreamWriter(worker.getOutputStream)
  //     out.write(serverSocket.getLocalPort + "\n")
  //     out.flush()

  //     // Wait for it to connect to our socket
  //     serverSocket.setSoTimeout(10000)
  //     try {
  //       return serverSocket.accept()
  //     } catch {
  //       case e: Exception =>
  //         throw new SparkException("Worker did not connect back in time", e)
  //     }
  //   } finally {
  //     if (serverSocket != null) {
  //       serverSocket.close()
  //     }
  //   }
  //   null
  // }

  var daemon: Process = null
  val daemonHost = InetAddress.getByAddress(Array(127, 0, 0, 1))
  var daemonPort: Int = 0

  val daemonWorker = workerDir+"/daemon.rb"

  def create(): Socket = {
    synchronized {
      // Start the daemon if it hasn't been started
      startDaemon()

      // Attempt to connect, restart and retry once if it fails
      try {
        new Socket(daemonHost, daemonPort)
      } catch {
        case exc: SocketException =>
          logWarning("Worker daemon unexpectedly quit, attempting to restart")
          stopDaemon()
          startDaemon()
          new Socket(daemonHost, daemonPort)
      }
    }
  }

  private def startDaemon() {
    synchronized {
      // Is it already running?
      if (daemon != null) {
        return
      }

      try {
        // Create and start the daemon
        // val pb = new ProcessBuilder(Seq("ruby", daemonWorker))
        val pb = new ProcessBuilder(daemonWorker)
        // pb.environment().put("", "")
        daemon = pb.start()

        val in = new DataInputStream(daemon.getInputStream)
        daemonPort = in.readInt()

        // Redirect daemon stdout and stderr
        redirectStreamsToStderr(in, daemon.getErrorStream)

      } catch {
        case e: Exception =>

          // If the daemon exists, wait for it to finish and get its stderr
          val stderr = Option(daemon).flatMap { d => Utils.getStderr(d, PROCESS_WAIT_TIMEOUT_MS) }
                                     .getOrElse("")

          stopDaemon()

          if (stderr != "") {
            val formattedStderr = stderr.replace("\n", "\n  ")
            // val errorMessage = s"""
            //   |Error from python worker:
            //   |  $formattedStderr
            //   |PYTHONPATH was:
            //   |  $pythonPath
            //   |$e"""
            val errorMessage = ""

            // Append error message from python daemon, but keep original stack trace
            val wrappedException = new SparkException(errorMessage.stripMargin)
            wrappedException.setStackTrace(e.getStackTrace)
            throw wrappedException
          } else {
            throw e
          }
      }

      // Important: don't close daemon's stdin (daemon.getOutputStream) so it can correctly
      // detect our disappearance.
    }
  }

  private def stopDaemon() {
    synchronized {
      // Request shutdown of existing daemon by sending SIGTERM
      if (daemon != null) {
        daemon.destroy()
      }

      daemon = null
      daemonPort = 0
    }
  }

  private def redirectStreamsToStderr(stdout: InputStream, stderr: InputStream) {
    try {
      new RedirectThread(stdout, System.err, "stdout reader").start()
      new RedirectThread(stderr, System.err, "stderr reader").start()
    } catch {
      case e: Exception =>
        logError("Exception in redirecting streams", e)
    }
  }

}
