//#full-example
package com.example
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.util.Timeout

import scala.concurrent.{Await, ExecutionContext, Future}
import akka.annotation.InternalApi

import scala.concurrent.blocking
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.dispatch._
import akka.dispatch.sysmsg._
import akka.event.AddressTerminatedTopic
import akka.event.EventStream
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.serialization.JavaSerializer
import akka.serialization.Serialization
import com.typesafe.config.ConfigFactory
import akka.util.{OptionVal, Timeout}
import akka.pattern.ask

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import akka.dispatch.Mailbox
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._


//#greeter-companion
//#greeter-messages


//case class AskControlMessage(message: Any, asker: ActorRef) extends MyControlMessage
////case class TellPresToSenderControlMessage(message: Any, flag: Boolean) extends MyControlMessage
//case class TellControlMessage(message: Any, flag: Boolean) extends MyControlMessage
//case class NotifyControlMessage(message: Any) extends MyControlMessage

object Greeter {
  //#greeter-messages
  def props(message: String, printerActor: ActorRef): Props = Props(new Greeter(message, printerActor))
  //#greeter-messages
  final case class WhoToGreet(who: String)
  case object Greet
  case class NormalMessage(message: String)
  case class TestMessage(message: String, from: ActorRef)
  case class AskControlMessage(message: MyTransition, asker: ActorRef) extends MyControlMessage
  case class TellControlMessage(message: Any, flag: Boolean) extends MyControlMessage
  case class NotifyControlMessage(asker: ActorRef) extends MyControlMessage
  case class StashedNormalMessage(message: NormalMessage) extends StashedMessage
  case class StashedAskMessage(message: AskControlMessage) extends StashedMessage
  case class SendOrderMessage(from: ActorRef, to: ActorRef, message: Any, automata: Automata)
}
//#greeter-messages
//#greeter-companion

//#greeter-actor
class Greeter(message: String, printerActor: ActorRef) extends Actor{
  import Greeter._
  import Printer._

  var greeting = ""
  var unNotified: Vector[ActorRef] = Vector[ActorRef]()
  //myNote
  //history defined here, i guess this is right, but maybe you need to change it.
  var history: Vector[MyTransition] = Vector[MyTransition]()
  var stashNormalQueue: Vector[StashedNormalMessage] = Vector[StashedNormalMessage]()
  var stashAskQueue: Vector[StashedAskMessage] = Vector[StashedAskMessage]()
  implicit val ec: ExecutionContext = context.dispatcher

  def synchronizedMonitoring(sender: ActorRef,transitions: Vector[MyTransition], transitionStatus: Vector[Int],  automata: Automata): Boolean ={

      var res: Boolean = false
      for (transition ← transitions) {
        val pres: Vector[MyTransition] = automata.singleFindPre(transition)
        var tellList: Vector[Future[TellControlMessage]] = Vector.empty[Future[TellControlMessage]]
        for (pre ← pres) {
          val msg: MessageBundle = pre.messageBundle
          //send transition not msg
          val ctrlMsg = AskControlMessage(MyTransition(pre.from, pre.to, msg, true), sender)
          implicit val timeout = Timeout(10.seconds)
          val future: Future[TellControlMessage] = (msg.s ? ctrlMsg).mapTo[TellControlMessage]
          tellList = tellList :+ future
        }
        var preSent: Int = 0
        val all = Future.sequence(tellList)
        Await.result(all, 10 seconds)
        for(tellRes <- all.value.get.get){
          if(tellRes.flag == true){
            preSent = preSent + 1
          }
        }
        if(preSent >= 1){
            if (automata.isLastTransition(transition)) {

              //              val f : Future[Boolean] = Future{true}
              println("inside im sending error")
               return true
              //              f
            }
        }
//        var preSent: Int = 0
//        all.andThen {
//          case scala.util.Success(result) ⇒ {
//            for (tellRes ← result) {
//              if (tellRes.flag == true) {
//                preSent = preSent + 1
//              }
//            }
//            if (preSent >= 1) {
//              if (automata.isLastTransition(transition)) {
//
//                //              val f : Future[Boolean] = Future{true}
//                println("inside im sending error")
//                res = true
//                //              f
//              }
//            }
//            //transitionStatus = transitionStatus :+ preSent
//          }
//        }.andThen {
//          case Failure(e) => println(e)
//        }
        //        all.onComplete {
        //          case scala.util.Success(result) ⇒ {
        //            for (tellRes ← result) {
        //              if (tellRes.flag == true) {
        //                preSent = preSent + 1
        //              }
        //            }
        //            if (preSent >= 1) {
        //              if (automata.isLastTransition(transition)) {
        //
        //                //              val f : Future[Boolean] = Future{true}
        //                println("inside im sending error")
        //                true
        //                //              f
        //              }
        //            }
        //            //transitionStatus = transitionStatus :+ preSent
        //          }
        //          case Failure(e) => println(e)
        //        }
      }


      //    val f : Future[Boolean] = Future{false}
      //    f
    false
  }
  def sendSecureMessage(sender: ActorRef,receiver: ActorRef, message: Any, automata: Automata): Unit = {
    //MyNote
    //TODO preDispatch
    //search for pres in automata
    //send ask messages to pres
    //w8 for response
    //decide what to do (send message or abort)

    //assume that we have the automata in the Actor

    val msgBundle: MessageBundle = new MessageBundle(sender, message, receiver)
    //for all transitions
    val transitions = automata.findTransitionByMessageBundle(msgBundle)
    var transitionStatus: Vector[Int] = Vector.empty[Int]
    var allPres: Vector[MyTransition] = Vector.empty[MyTransition]
    println("im here with future " + self.path)
    if(synchronizedMonitoring(sender ,transitions, transitionStatus, automata)){
        println("im here to send error")
        receiver ! NormalMessage("error")
    }
    else{
        println("im here to send normal" + " " + self.path)
        for (transition <- transitions) {
          history = history :+ transition
        }
        receiver ! message
    }
    for (transition <- transitions) {
      val pres: Vector[MyTransition] = automata.singleFindPre(transition)
      allPres = allPres ++ pres
    }

    for (pre ← allPres) {
      val msg: MessageBundle = pre.messageBundle
      val notifMsg = NotifyControlMessage(sender)
      msg.s ! notifMsg
    }
    //          for (transition <- transitions) {
    //            val pres: Vector[MyTransition] = automata.singleFindPre(transition)
    //            allPres = allPres ++ pres
    //          }
    //
    //          for (pre ← allPres) {
    //            val msg: MessageBundle = pre.messageBundle
    //            val notifMsg = NotifyControlMessage(sender)
    //            msg.s ! notifMsg
    //          }

//    val errorSent : Future[Boolean] = Future.apply(synchronizedMonitoring(sender ,transitions, transitionStatus, automata))
//      errorSent.onComplete {
//        case scala.util.Success(result) ⇒ {
//          if (result) {
//            println("im here to send error")
//            receiver ! NormalMessage("error")
//          }
//          if (!result) {
//            println("im here to send normal" + " " + self.path)
//            for (transition <- transitions) {
//              history = history :+ transition
//            }
//            receiver ! message
//          }
//          for (transition <- transitions) {
//            val pres: Vector[MyTransition] = automata.singleFindPre(transition)
//            allPres = allPres ++ pres
//          }
//
//          for (pre ← allPres) {
//            val msg: MessageBundle = pre.messageBundle
//            val notifMsg = NotifyControlMessage(sender)
//            msg.s ! notifMsg
//          }
//        }
//        case Failure(e) => println(e)
//      }

    //send notif
    // val msgBundle: MessageBundle = new MessageBundle(receiver, message, sender)
    // val transitions = automata.findTransitionByMessageBundle(msgBundle)
    // automata.findPre(transitions)
    // val msgB: MessageBundle = new MessageBundle(msgB.sender, msgB.message,msgB.receiver) //TODO change to message
    // val answer:Boolean = sendAskMessagesAndGetFlag(msgB)

    // if (answer)
    //   this.!(message, sender)
    //MyNote
    //TODO check again
    //double check "history" implementation
  }
  val manageControls : Receive = {
//    case WhoToGreet(who) =>
//      print("WhoToGreet")
//      greeting = message + ", " + who
//    case Greet           =>
//      //#greeter-send-message
//      print("printerActor")
//      printerActor ! Greeting(greeting)
//      //#greeter-send-message
    case TestMessage(message, from) => {
      println("I GOT IT" + " " + self.path.name)
      sender() ! NormalMessage("RECEIVED IT")
    }
    case SendOrderMessage(from, to, message, automata) => {
//      val future: Future[Any] = (ask(to, message)(5 seconds))
//      //Await.result(future, 10 seconds)
//      val futureMessage: Future[NormalMessage] = future.mapTo[NormalMessage]
//      futureMessage.onComplete{
//        case(scala.util.Success(result) ) => print(result + " " + self.path.name)
//      }
//      println("the value of the future: " + futureMessage.value  + " " + self.path.name )
//      println("this is done after completion of future" + " " + self.path.name)
      sendSecureMessage(from, to, message, automata)
    }
    case StashedAskMessage(message) => {
      println("Stashed Ask Message" + " " + self.path.name)
      val tellControlMessage: TellControlMessage = tellStatusToSender(message.message.from, message.message.to, message.message.messageBundle,message.message.regTransition,message.asker)
      sender() ! tellControlMessage
    }

    case NotifyControlMessage(asker) => {
      println("Notify message" + " " + self.path.name)
      unNotified = unNotified.filter(_ != asker)
      // i assume that here unnotified just became empty cause we dont get notify message
      // unless we have something in unnotified so if its empty now it's just became empty
      if (unNotified.isEmpty) {
        while (!stashAskQueue.isEmpty) {
          self ! stashAskQueue.last.message
          stashAskQueue = stashAskQueue.init
        }
        while (!stashNormalQueue.isEmpty) {
          self ! stashNormalQueue.last.message
          stashNormalQueue = stashNormalQueue.init
        }
      }
    }
    case AskControlMessage(message, asker) =>
      if(unNotified.isEmpty) {
        println("Not Stashed Ask Message" + " " + self.path.name + " " + message.messageBundle.m)
        val tellControlMessage: TellControlMessage = tellStatusToSender(message.from, message.to, message.messageBundle,message.regTransition,asker)
        sender() ! tellControlMessage
      }
      else {
        val askmsg: AskControlMessage = AskControlMessage(message, asker)
        stashAskQueue = stashAskQueue :+ StashedAskMessage(askmsg)
      }



  }
  val manageNormals: Receive= {
    case NormalMessage(message) =>
      if(unNotified.isEmpty) {
        println("Not Stashed" + message + " " + self.path.name)

      } else
        stashNormalQueue = stashNormalQueue :+ StashedNormalMessage(NormalMessage(message))

    case StashedNormalMessage(message) => {
      println("Stashed Normal Message" + " " + self.path.name )
    }
  }

  def receive = manageControls.orElse(manageNormals)
  def tellStatusToSender(from: Int, to: Int, messageBundle: MessageBundle, regTransiton: Boolean, asker: ActorRef): TellControlMessage ={
          var answer = true
          val transition: MyTransition = MyTransition(from, to, messageBundle, regTransiton)
          if (history.contains(transition))
            answer = true
          else
            answer = false
          //chizi ke baramoon oomade Pre e
          val tellControlMessage: TellControlMessage = TellControlMessage(transition, answer)
          //TODO check history and automata, send a tell message to sender with msg and true/false, sender will erase that msg from his "Pres set"
          //assumed that tell does just like !
          println(tellControlMessage)
          // could be asker ! tellControlMessage
          unNotified = unNotified :+ asker

          tellControlMessage
  }
}
//#greeter-actor

//#printer-companion
//#printer-messages
object Printer {
  //#printer-messages
  def props: Props = Props[Printer]
  //#printer-messages
  final case class Greeting(greeting: String)
}
//#printer-messages
//#printer-companion

//#printer-actor
class Printer extends Actor with ActorLogging {
  import Printer._

  def receive = {

    case Greeting(greeting) =>{
      log.info("Greeting received (from " + sender() + "): " + greeting)
    }
    case _=>{
      print("greeting")
    }


  }
}
//#printer-actor

//#main-class
object AkkaQuickstart extends App {
  import Greeter._

  // Create the 'helloAkka' actor system
  val system: ActorSystem = ActorSystem("helloAkka")

  //#create-actors
  // Create the printer actor
  val printer: ActorRef = system.actorOf(Printer.props, "printerActor")

  // Create the 'greeter' actors
//  val howdyGreeter: ActorRef =
//    system.actorOf(Greeter.props("Howdy", printer).withDispatcher("custom-dispatcher"),"howdyGreeter")
//  val helloGreeter: ActorRef =
//    system.actorOf(Greeter.props("Hello", printer).withDispatcher("custom-dispatcher"), "helloGreeter")
//  val goodDayGreeter: ActorRef =
//    system.actorOf(Greeter.props("Good day", printer).withDispatcher("custom-dispatcher"), "goodDayGreeter")
//  //#create-actors
//  //#main-send-messages
//  howdyGreeter ! WhoToGreet("Akka")
//  howdyGreeter ! Greet
//
//  howdyGreeter ! WhoToGreet("Lightbend")
//  howdyGreeter ! Greet
//
//  helloGreeter ! WhoToGreet("Scala")
//  helloGreeter ! Greet
//
//  goodDayGreeter ! WhoToGreet("Play")
//  goodDayGreeter ! Greet
//  //#main-send-messages
  val firstActor: ActorRef =
    system.actorOf(Greeter.props("first",printer).withDispatcher("custom-dispatcher"),"firstActor")
  val secondActor: ActorRef =
    system.actorOf(Greeter.props("second",printer).withDispatcher("custom-dispatcher"),"secondActor")
  //val customNormalMessage: NormalMessage = NormalMessage("Hello my name is Sina")
  val customAutomata: Automata = new Automata
  val customBundle: MessageBundle = new MessageBundle(secondActor, NormalMessage("a0"), firstActor)
  val customTransition: MyTransition = new MyTransition(0,1, customBundle ,true)
  val customBundle2: MessageBundle = new MessageBundle(firstActor, NormalMessage("b1"),secondActor)
  val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
  customAutomata.addTransition(customTransition)
  customAutomata.addTransition(customTransition2)
  customAutomata.addLastTransition(2)
  secondActor ! SendOrderMessage(secondActor, firstActor, NormalMessage("a0"), customAutomata)
  firstActor ! SendOrderMessage(firstActor, secondActor, NormalMessage("b1"), customAutomata)
}


//#main-class
//#full-example
