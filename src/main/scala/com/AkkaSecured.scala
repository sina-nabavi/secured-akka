package com

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import scala.concurrent.{Await}
import akka.util.{Timeout}
import akka.pattern.ask
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


object SecureActor {
  def props(): Props = Props(new SecureActor())
  final case class WhoToGreet(who: String)
  val OverallTimeOut = 10 seconds
  //add error type
  case class NormalMessage(message: String)
  case class ErrorMessage() extends MyControlMessage
  case class AskControlMessage(message: MyTransition, asker: ActorRef) extends MyControlMessage
  case class TellControlMessage(message: Any, flag: Boolean) extends MyControlMessage
  case class NotifyControlMessage(asker: ActorRef) extends MyControlMessage
  case class StashedNormalMessage(message: NormalMessage) extends StashedMessage
  case class StashedAskMessage(message: AskControlMessage) extends StashedMessage
  case class SendOrderMessage(to: ActorRef, message: Any, automata: Automata)
}


class SecureActor extends Actor{
  import SecureActor._
  var greeting = ""
  var unNotified: Vector[ActorRef] = Vector[ActorRef]()
  //history defined here, i guess this is right, but maybe you need to change it.
  var history: Vector[MyTransition] = Vector[MyTransition]()
  var stashNormalQueue: Vector[StashedNormalMessage] = Vector[StashedNormalMessage]()
  var stashAskQueue: Vector[StashedAskMessage] = Vector[StashedAskMessage]()
  implicit val ec: ExecutionContext = context.dispatcher

  def sendNotifications(transitions:Vector[MyTransition], automata: Automata): Unit={
    var allPres: Vector[MyTransition] = Vector.empty[MyTransition]
    for (transition <- transitions) {
      val pres: Vector[MyTransition] = automata.singleFindPre(transition)
      allPres = allPres ++ pres
    }
    for (pre ← allPres) {
      val msg: MessageBundle = pre.messageBundle
      val notifMsg = NotifyControlMessage(self)
      msg.s ! notifMsg
    }
  }
  // transition status is still not used
  def synchronizedMonitoring(transitions: Vector[MyTransition], transitionStatus: Vector[Int],  automata: Automata): Boolean ={
      for (transition ← transitions) {
        val pres: Vector[MyTransition] = automata.singleFindPre(transition)
        var tellList: Vector[Future[TellControlMessage]] = Vector.empty[Future[TellControlMessage]]
        for (pre ← pres) {
          val msg: MessageBundle = pre.messageBundle
          val ctrlMsg = AskControlMessage(MyTransition(pre.from, pre.to, msg, true), self)
          implicit val timeout = Timeout(10.seconds)
          val future: Future[TellControlMessage] = (msg.s ? ctrlMsg).mapTo[TellControlMessage]
          tellList = tellList :+ future
        }
        var preSent: Int = 0
        val all = Future.sequence(tellList)
        Await.result(all, SecureActor.OverallTimeOut)
        for(tellRes <- all.value.get.get){
          if(tellRes.flag == true){
            preSent = preSent + 1
          }
        }
        // in the original problem we should check whether all the messages are sent or not
        if(preSent >= 1){
            if (automata.isLastTransition(transition)) {
               return true
            }
        }
      }
    false
  }

  def sendSecureMessage(receiver: ActorRef, message: Any, automata: Automata): Unit = {
    // assume that we have the automata in the Actor
    val msgBundle: MessageBundle = new MessageBundle(self, message, receiver)
    //for all transitions
    val transitions = automata.findTransitionByMessageBundle(msgBundle)
    val transitionStatus: Vector[Int] = Vector.empty[Int]
    if(synchronizedMonitoring(transitions, transitionStatus, automata)){
        //should send error type message
        receiver ! ErrorMessage
    }
    else{
        //println("im here to send normal" + " " + self.path)
        for (transition <- transitions) {
          history = history :+ transition
        }
        receiver ! message
    }
    sendNotifications(transitions, automata)
  }

  val manageControls : Receive = {
    case SendOrderMessage(to, message, automata) => {
      sendSecureMessage(to, message, automata)
    }
    case ErrorMessage => {
      println("Error Message" + " " + self.path.name)
    }
    case StashedAskMessage(message) => {
      println("Stashed Ask Message" + " " + self.path.name)
      val tellControlMessage: TellControlMessage = tellStatusToSender(message.message.from, message.message.to, message.message.messageBundle,message.message.regTransition,message.asker)
      sender() ! tellControlMessage
    }

    case NotifyControlMessage(asker) => {
      println("Notify Message" + " " + self.path.name)
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
        println("Not Stashed Ask Message " + " " + self.path.name + " " + message.messageBundle.m)
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
        println("Not Stashed Normal Message " + message + " " + self.path.name)

      } else
        stashNormalQueue = stashNormalQueue :+ StashedNormalMessage(NormalMessage(message))

    case StashedNormalMessage(message) => {
      println("Stashed Normal Message" + " " + self.path.name)
    }
  }
  //user should write it
  def receive = manageControls.orElse(manageNormals)
  def tellStatusToSender(from: Int, to: Int, messageBundle: MessageBundle, regTransiton: Boolean, asker: ActorRef): TellControlMessage ={
          var answer = true
          val transition: MyTransition = MyTransition(from, to, messageBundle, regTransiton)
          if (history.contains(transition))
            answer = true
          else
            answer = false
          val tellControlMessage: TellControlMessage = TellControlMessage(transition, answer)
          //TODO check history and automata, send a tell message to sender with msg and true/false, sender will erase that msg from his "Pres set"
          //assumed that tell does just like !
          println(tellControlMessage)
          // could be asker ! tellControlMessage
          unNotified = unNotified :+ asker
          tellControlMessage
  }
}


object MainApp extends App {
  import SecureActor._
  val system: ActorSystem = ActorSystem("helloAkka")
  val firstActor: ActorRef =
    system.actorOf(SecureActor.props().withDispatcher("custom-dispatcher"),"firstActor")
  val secondActor: ActorRef =
    system.actorOf(SecureActor.props().withDispatcher("custom-dispatcher"),"secondActor")
  val customAutomata: Automata = new Automata
  val customBundle: MessageBundle = new MessageBundle(secondActor, NormalMessage("a0"), firstActor)
  val customTransition: MyTransition = new MyTransition(0,1, customBundle ,true)
  val customBundle2: MessageBundle = new MessageBundle(firstActor, NormalMessage("b1"),secondActor)
  val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
  customAutomata.addTransition(customTransition)
  customAutomata.addTransition(customTransition2)
  customAutomata.addLastTransition(2)
  secondActor ! SendOrderMessage(firstActor, NormalMessage("a0"), customAutomata)
  firstActor ! SendOrderMessage(secondActor, NormalMessage("b1"), customAutomata)
}
