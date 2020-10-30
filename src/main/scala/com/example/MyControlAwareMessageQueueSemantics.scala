package com.example

import java.util
import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch._
import com.typesafe.config.Config
import java.util.{Comparator, Deque, PriorityQueue, Queue}

trait MyControlAwareMessageQueueSemantics

object MyControlAwareMailbox {
  class MyControlAwareMessageQueue extends MessageQueue with MyControlAwareMessageQueueSemantics {

    private final val stashedQueue: Queue[Envelope] = new ConcurrentLinkedQueue[Envelope]()
    private final val controlQueue: Queue[Envelope] = new ConcurrentLinkedQueue[Envelope]()
    private final val queue: Queue[Envelope] = new ConcurrentLinkedQueue[Envelope]()


    //MyNote
    //you should implement the algorithm here.
    //give green light to sender if the message should be sent.
//    def tellStatusToSender(from: Int, to: Int, messageBundle: MessageBundle, regTransiton: Boolean, asker: ActorRef): Unit =
//    {
//
//      var answer = true
//      val transition: MyTransition = new MyTransition(from, to, messageBundle, regTransiton)
//      if (history.contains(transition))
//        answer = true
//      else
//        answer = false
//      //chizi ke baramoon oomade Pre e
//      val tellControlMessage: TellControlMessage = new TellControlMessage(transition, answer)
//      //TODO check history and automata, send a tell message to sender with msg and true/false, sender will erase that msg from his "Pres set"
//      //assumed that tell does just like !
//      asker.tell(tellControlMessage, asker) // could be asker ! tellControlMessage
//      unNotified = unNotified :+ asker
//    }
    override def enqueue(receiver: ActorRef, handle: Envelope): Unit = handle match {
      case envelope @ Envelope(_: StashedMessage, _) => stashedQueue add envelope
      case envelope @ Envelope(_: MyControlMessage, _) ⇒ controlQueue add envelope
      case envelope ⇒ queue add envelope
    }

    override def dequeue(): Envelope =
    {
      if(!stashedQueue.isEmpty){
        print("Stashed Message Polled")
        stashedQueue.poll()
      }
      //if we want to block the actor wholly we can put a while(unNotified.isempty() == false) here to pop till empty
      if (!controlQueue.isEmpty) {
        val controlMsg = controlQueue.poll()
//        controlMsg.message match {
//          case AskControlMessage(MyTransition(from, to, messageBundle, regTransition), asker) ⇒ tellStatusToSender(from, to, messageBundle, regTransition, asker)
//          case NotifyControlMessage(asker) ⇒ unNotified.filter(_ != asker)
//          case _ ⇒ print(s"match case failed")
//        }
        controlMsg

      }
      else {
        queue.poll()
      }

    }

    override def numberOfMessages: Int = queue.size() + controlQueue.size()

    override def hasMessages: Boolean = !(queue.isEmpty && controlQueue.isEmpty)

    //TODO check cleanUp!niaz darim besh ya na?

    override def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit =
      while (hasMessages)
        deadLetters.enqueue(owner, dequeue())
  }
}

class MyControlAwareMailbox extends MailboxType with ProducesMessageQueue[MyControlAwareMailbox.MyControlAwareMessageQueue] {
  import MyControlAwareMailbox._

  def this(settings: ActorSystem.Settings, config: Config) = this()

  override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue = new MyControlAwareMessageQueue()
}
/**
 * Messages that extend this trait will be handled with priority by control aware mailboxes.
 */
trait MyControlMessage
trait StashedMessage
//case class AskControlMessage(message: Any, asker: ActorRef) extends MyControlMessage
////case class TellPresToSenderControlMessage(message: Any, flag: Boolean) extends MyControlMessage
//case class TellControlMessage(message: Any, flag: Boolean) extends MyControlMessage
//case class NotifyControlMessage(message: Any) extends MyControlMessage
