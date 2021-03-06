package com.twitter.finagle.kestrel

import com.twitter.finagle.kestrel.protocol._
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.buffer.ChannelBuffers._
import com.twitter.conversions.time._
import _root_.java.util.concurrent.{TimeUnit, BlockingDeque}
import com.twitter.finagle.Service
import com.twitter.util.{StateMachine, Future}
import com.twitter.util.StateMachine.InvalidStateTransition
import com.google.common.cache.LoadingCache

class Interpreter(queues: LoadingCache[ChannelBuffer, BlockingDeque[ChannelBuffer]]) extends StateMachine {
  case class NoTransaction() extends State
  case class OpenTransaction(queueName: ChannelBuffer, item: ChannelBuffer) extends State
  state = NoTransaction()

  def apply(command: Command): Response = {
    command match {
      case Get(queueName, timeout) =>
        state match {
          case NoTransaction() =>
            val wait = timeout.getOrElse(0.seconds)
            val item = queues.get(queueName).poll(wait.inMilliseconds, TimeUnit.MILLISECONDS)
            if (item eq null)
              Values(Seq.empty)
            else
              Values(Seq(Value(wrappedBuffer(queueName), wrappedBuffer(item))))
          case OpenTransaction(txnQueueName, item) =>
            throw new InvalidStateTransition("Transaction", "get")
        }
      case Open(queueName, timeout) =>
        state match {
          case NoTransaction() =>
            val wait = timeout.getOrElse(0.seconds)
            val item = queues.get(queueName).poll(wait.inMilliseconds, TimeUnit.MILLISECONDS)
            state = OpenTransaction(queueName, item)
            if (item eq null)
              Values(Seq.empty)
            else
              Values(Seq(Value(wrappedBuffer(queueName), wrappedBuffer(item))))
          case OpenTransaction(txnQueueName, item) =>
            throw new InvalidStateTransition("Transaction", "get/open")
        }
      case Close(queueName, timeout) =>
        state match {
          case NoTransaction() =>
            throw new InvalidStateTransition("Transaction", "get/close")
          case OpenTransaction(txnQueueName, _) =>
            require(queueName == txnQueueName,
              "Cannot operate on a different queue than the one for which you have an open transaction")
            state = NoTransaction()
            Values(Seq.empty)
        }
      case CloseAndOpen(queueName, timeout) =>
        state match {
          case NoTransaction() =>
            throw new InvalidStateTransition("Transaction", "get/close/open")
          case OpenTransaction(txnQueueName, _) =>
            require(queueName == txnQueueName,
              "Cannot operate on a different queue than the one for which you have an open transaction")
            state = NoTransaction()
            apply(Open(queueName, timeout))
        }
      case Abort(queueName, timeout) =>
        state match {
          case NoTransaction() =>
            throw new InvalidStateTransition("Transaction", "get/abort")
          case OpenTransaction(txnQueueName, item) =>
            require(queueName == txnQueueName,
              "Cannot operate on a different queue than the one for which you have an open transaction")

            queues.get(queueName).addFirst(item)
            state = NoTransaction()
            Values(Seq.empty)
        }
      case Peek(queueName, timeout) =>
        val wait = timeout.getOrElse(0.seconds)
        val item = queues.get(queueName).poll(wait.inMilliseconds, TimeUnit.MILLISECONDS)
        if (item eq null)
          Values(Seq.empty)
        else
          Values(Seq(Value(wrappedBuffer(queueName), wrappedBuffer(item))))
      case Set(queueName, expiry, value) =>
        queues.get(queueName).add(value)
        Stored()
      case Delete(queueName) =>
        queues.invalidate(queueName)
        Deleted()
      case Flush(queueName) =>
        queues.invalidate(queueName)
        Deleted()
      case FlushAll() =>
        queues.invalidateAll()
        Deleted()
      case Version() =>
        NotFound()
      case ShutDown() =>
        NotFound()
      case DumpConfig() =>
        NotFound()
      case Stats() =>
        NotFound()
      case DumpStats() =>
        NotFound()
      case Reload() =>
        NotFound()
    }
  }
}

class InterpreterService(interpreter: Interpreter) extends Service[Command, Response] {
  def apply(request: Command) = Future(interpreter(request))
  override def release() = ()
}
