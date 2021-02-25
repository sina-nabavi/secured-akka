package com

import java.util.concurrent.TimeoutException

import akka.actor.{Actor, ActorRef, ActorSystem, Props}

import scala.concurrent.Await
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

//we can only transmit vector clock on Normal Message to reduce the cost

object SecureActor {
  def props(): Props = Props(new SecureActor())
  val OverallTimeOut = 10 seconds
  val MaxActorNumber: Int = 100
  //add error type
  //Ask if we should send by value: I guessed that actors wont change it so its okay
  case class NormalMessageWithVectorClock(message: Any, vc: Array[Int])
  case class NormalMessage(message: Any)
  case class ErrorMessage(vc: Array[Int]) extends MyControlMessage
  case class AskControlMessage(message: MyTransition, asker: ActorRef, dest: ActorRef, msgVc: Array[Int], inspectedTransition: MyTransition, isBlocked: Boolean) extends MyControlMessage
  //inquired transition
  case class TellControlMessage(message: MyTransition, teller: ActorRef, dest: ActorRef, vc: Array[Int], msgVC: Array[Int], inspectedTransition: MyTransition, repRecs: Vector[(MyTransition, Array[Int], String)]) extends MyControlMessage
  case class NotifyControlMessage(asker: ActorRef, vc: Array[Int]) extends MyControlMessage
  case class StashedNormalMessage(message: NormalMessageWithVectorClock) extends StashedMessage
  case class StashedAskMessage(message: AskControlMessage) extends StashedMessage
  //Did not include here to make it transparent
  case class SendOrderMessage(to: ActorRef, message: Any)
  case class SetAutomata(automata:Automata)
}


class SecureActor extends Actor{
  import SecureActor._
  var automata: Automata = null
  val name: String = self.path.name
  val hash: Int = (name.hashCode() % MaxActorNumber).abs
  //there is a 1/100 chance for two strings to have a same hash
  println("my name is " + name + " and my vector clock index is " + hash)
  var vectorClock: Array[Int] = Array.fill(MaxActorNumber)(0)
  var greeting = ""
  var unNotified: Vector[ActorRef] = Vector[ActorRef]()
  //history defined here, i guess this is right, but maybe you need to change it.
  var history: Vector[(MyTransition, Array[Int], String)] = Vector[(MyTransition, Array[Int], String)]()
  var stashNormalQueue: Vector[NormalMessageWithVectorClock] = Vector[NormalMessageWithVectorClock]()
  var stashAskQueue: Vector[AskControlMessage] = Vector[AskControlMessage]()
  //make this transition
  var pendingAsk: Map[(MyTransition, Array[Int]), Vector[(MyTransition, Array[Int])]]= Map() // immutable
  var receivedResponse: Map[(MyTransition, Array[Int]), Vector[(MyTransition, Array[Int], String)]] = Map()
  // Make it list of control messages
  //omit the transition
  var pendingMonitorMessage: Vector[(AskControlMessage)] = Vector[AskControlMessage]()
  implicit val ec: ExecutionContext = context.dispatcher
  // what about our own clock value if it's bigger in other actor's vc?
  def updateVectorClock(vc: Array[Int]): Unit={
    for(i <- 0 to MaxActorNumber - 1){
      if(vc(i) > vectorClock(i))
        vectorClock(i) = vc(i)
    }
  }

  def vectorClockLess(vc1: Array[Int], vc2: Array[Int]): Boolean={
    for(i <- 0 to MaxActorNumber - 1){
      if(vc1(i) > vc2(i))
        false
    }
    !(vc1 == vc2)
  }

  def vectorClockConcurent(vc1: Array[Int], vc2: Array[Int]): Boolean={
    var hasLess: Boolean = false
    var hasGreater: Boolean = false
    for(i <- 0 to MaxActorNumber - 1){
      if(vc1(i) < vc2(i))
        hasLess = true
      if(vc1(i) > vc2(i))
        hasGreater = true
    }
    (hasLess && hasGreater) || (vc1 == vc2)
  }

  def vectorClockNotGreater(vc1: Array[Int], vc2: Array[Int]): Boolean={
    vectorClockLess(vc1,vc2) || vectorClockConcurent(vc1,vc2)
  }

  def sendNotifications(transitions:Vector[MyTransition], automata: Automata): Unit={
    Thread.sleep(5000)
    var allPres: Vector[MyTransition] = Vector.empty[MyTransition]
    for (transition <- transitions) {
      val pres: Vector[MyTransition] = automata.singleFindPre(transition)
      allPres = allPres ++ pres
    }
    for (pre ← allPres) {
      val msg: MessageBundle = pre.messageBundle
      val notifMsg = NotifyControlMessage(self,vectorClock)
      msg.s ! notifMsg
    }
  }
  // transition status is still not used
  def asynchronizedMonitoring(transitions: Vector[MyTransition], transitionStatus: Vector[Int],  automata: Automata): Unit ={
    //build awaiting tell messages
    for (transition ← transitions) {
      val pendingIndex = (transition, vectorClock)
      var pendingList : Vector[(MyTransition, Array[Int])] = Vector[(MyTransition, Array[Int])]()
      val presAndVios: Vector[MyTransition] = automata.singleFindPre(transition) ++ automata.singleFindVio(transition)
      for (pre ← presAndVios) {
        val msg: MessageBundle = pre.messageBundle
        val ctrlMsg = AskControlMessage(MyTransition(pre.from, pre.to, msg, true), self, msg.s, vectorClock, transition, false)
        msg.s ! ctrlMsg
        pendingList = pendingList :+ (pre, vectorClock)
      }
      addToHistory(transitions)
      receivedResponse += (pendingIndex -> Vector[(MyTransition, Array[Int], String)]())
      pendingAsk += (pendingIndex -> pendingList)
    }
  }
  def synchronizedMonitoring(transitions: Vector[MyTransition], transitionStatus: Vector[Int]): Boolean ={
      for (transition ← transitions) {
        val presAndVios: Vector[MyTransition] = automata.singleFindPre(transition) ++ automata.singleFindVio(transition)
        var tellList: Vector[Future[TellControlMessage]] = Vector.empty[Future[TellControlMessage]]
        //name it correctly : other than pre
        for (pre ← presAndVios) {
          val msg: MessageBundle = pre.messageBundle
          //why construct my transition from scratch? pre is not good enough?
          var isBlocked: Boolean = false
//          if (automata.isLastTransition(pre))
//            isBlocked = true
          val ctrlMsg = AskControlMessage(MyTransition(pre.from, pre.to, msg, true), self, msg.s, vectorClock,transition, true)
          implicit val timeout = Timeout(OverallTimeOut)
          val future: Future[TellControlMessage] = (msg.s ? ctrlMsg).mapTo[TellControlMessage]
          tellList = tellList :+ future
        }
        receivedResponse += ((transition, vectorClock) -> Vector[(MyTransition, Array[Int], String)]())
        addToHistory(transitions)
        var done: Boolean = false
        var timeOutMultiplier: Int = 1
        while(!done) {
          try {
            val all = Future.sequence(tellList)
            Await.result(all, SecureActor.OverallTimeOut * timeOutMultiplier)
            for (tellRes <- all.value.get.get) {
              val newRcvResp:  Vector[(MyTransition, Array[Int], String)] = receivedResponse(tellRes.inspectedTransition, vectorClock) ++ tellRes.repRecs
              receivedResponse += ((tellRes.inspectedTransition, vectorClock) -> newRcvResp)
              updateVectorClock(tellRes.vc)
            }
            val result = relaxedTellCheck(transition, vectorClock, true)
            if(result == true)
              return true
            else if(result == false)
              return false
            else{
              throw new Exception("unexpected result from relaxed tell check")
            }
            done = true
          } catch {
            case e: TimeoutException => {
              timeOutMultiplier *= 2
              manageAsks()
            }
          }
        }
//        // in the original problem we should check whether all the messages are sent or not
//        if(preSent >= 1){
//            if (automata.isLastTransition(transition)) {
//               return true
//            }
//        }
      }
    throw new Exception("could not decide on blocking checking")
    //false
  }

  def sendSecureMessage(receiver: ActorRef, message: NormalMessage): Unit = {
    // assume that we have the automata in the Actor
    val msgBundle: MessageBundle = new MessageBundle(self, message, receiver)
    //for all transitions
    val transitions = automata.findTransitionByMessageBundle(msgBundle)
    var isLast: Boolean = false
    for(transition <- transitions){
      if (automata.isLastTransition(transition)) {
        isLast = true
      }
    }
    if(isLast)
      sendBlocking(receiver, message, transitions)
    else
      sendNonBlocking(receiver, message, transitions)
  }

  def sendNonBlocking(receiver: ActorRef, message: SecureActor.NormalMessage, transitions: Vector[MyTransition]): Unit = {
    vectorClock(hash) += 1
    println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + message)
    //not used
    val transitionStatus: Vector[Int] = Vector.empty[Int]
    receiver ! NormalMessageWithVectorClock(message.message,vectorClock)
    asynchronizedMonitoring(transitions, transitionStatus, automata)
    //addToHistory(transitions)
  }

  def sendBlocking(receiver: ActorRef, message: NormalMessage,transitions: Vector[MyTransition] ): Unit = {
    //not used
    val transitionStatus: Vector[Int] = Vector.empty[Int]
    //    if(synchronizedMonitoring(transitions, transitionStatus, automata)){
    //      //should send error type message
    //      vectorClock(hash) += 1
    //      println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + "ERROR")
    //      receiver ! ErrorMessage(vectorClock)
    //    }
    if(synchronizedMonitoring(transitions, transitionStatus) == false){
      //println("im here to send normal" + " " + self.path)
      //addToHistory(transitions)
      vectorClock(hash) += 1
      println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + message)
      receiver ! NormalMessageWithVectorClock(message.message,vectorClock)
    }
    sendNotifications(transitions, automata)
  }

  def addToHistory(transitions: Vector[MyTransition]): Unit = {
    for (transition <- transitions) {
      val pres: Vector[MyTransition] = automata.singleFindPre(transition)
      if (pres.length == 0 && automata.isLastTransition(transition) == false)
        history = history :+ (transition, vectorClock, "frm")
      else
        history = history :+ (transition, vectorClock, "?")
    }
  }
  //what happens about true, sending the error, or false?
  def relaxedTellCheck(inspected: MyTransition, vc: Array[Int], isBlocking: Boolean): Any = {
    println("I AM EXECUTING RELAXED TELL CHECK")
    var historyUpdate: Boolean = false
    var status: String = null
    //val pres: Vector[MyTransition] = automata.singleFindPre(tellMessage.inspectedTransition)
    //var vios: Vector[MyTransition] = automata.singleFindVio(tellMessage.inspectedTransition)
    var presTriple: Vector[(MyTransition, Array[Int], String)] = Vector[(MyTransition, Array[Int], String)]()
    //finds pres that are in the recvdresponse(tellMessage.inspectedTransition, tellMessage.msgVC)
    presTriple = receivedResponse(inspected, vc).filter(_._1.regTransition == true)
//    for(triple <- receivedResponse(inspected,vc)){
//      if(triple._1.regTransition == true){
//        presTriple = presTriple :+ triple
//      }
//    }
    var viosTriple: Vector[(MyTransition, Array[Int], String)] = Vector[(MyTransition, Array[Int], String)]()
    viosTriple = receivedResponse(inspected, vc).filter(_._1.regTransition == false)
    //finds vios that are in the recvdresponse(tellMessage.inspectedTransition, tellMessage.msgVC)
//    for(triple <- receivedResponse(inspected, vc)){
//      if(triple._1.regTransition == false){
//        viosTriple = viosTriple :+ triple
//      }
//    }
    for(triple <- presTriple){
      if(triple._3 == "frm" || triple._3 == "frmP") {
        if (vectorClockLess(triple._2, vc)) {
          var vioChecker: Boolean = false
          for (vio <- viosTriple) {
            if(vectorClockNotGreater(triple._2, vio._2)){
              vioChecker = true
            }
          }
          if(!vioChecker) {
            status = triple._3
            historyUpdate = true
          }
        }
      }
    }
    if(historyUpdate){
      //is it guarranteed to be in history? wont be necessary cause filterNot simply returns unmatched queries which can be all
      history = history filterNot (inspected, vc, "?").==
      history = history :+ (inspected, vc, status)
      if(isBlocking) {
        //should we send error here too?
        vectorClock(hash) += 1
        println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + "ERROR")
        inspected.messageBundle.r ! ErrorMessage(vectorClock)
      }
    }
    else {
      for (triple <- presTriple) {
          //add the vio condition
          if (vectorClockNotGreater(triple._2, vc)) {
            var vioLessChecker: Boolean = false
            var concurrencyChecker: Boolean = true
            for (vio <- viosTriple) {
              if(vectorClockLess(triple._2, vio._2)){
                vioLessChecker = true
              }
            }
            if(vectorClockConcurent(vc, triple._2))
              concurrencyChecker = false
            else{
              for (vio <- viosTriple) {
                if(vectorClockConcurent(triple._2, vio._2)){
                  concurrencyChecker = false
                }
              }
            }
            if(!vioLessChecker && !concurrencyChecker) {
              historyUpdate = true
            }
          }
          // if (isBlocking)
          // SEND ERROR
      }
      if(historyUpdate){
        history = history filterNot (inspected, vc, "?").==
        history = history :+ (inspected, vc, "frmP")
        //should we send error here too?
        if(isBlocking) {
          //should we send error here too?
          vectorClock(hash) += 1
          println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + "ERROR")
          inspected.messageBundle.r ! ErrorMessage(vectorClock)
        }
      }
      else{
        history = history filterNot (inspected, vc, "?").==
      }
    }
    handlePending(inspected)
    if(historyUpdate & isBlocking)
      true
  }

  def handlePending(transition: MyTransition): Unit = {
    //for(mm <- pendingMonitorMessage) {
      //      if(mm.inspectedTransition == transition){
      //        var res:  Vector[(MyTransition, Array[Int], String)] = Vector()
      //        for(triple <- history){
      //          if(triple._1 == transition && triple._3 == "?" && vectorClockLess(triple._2, mm.msgVc)){
      //            if(triple._3 == "?"){
      //              if(vectorClockConcurent(triple._2, mm.msgVc))
      //                res = res :+ (mm.message, triple._2, "frmP")
      //            }
      //            else if(vectorClockNotGreater(triple._2, mm.msgVc))
      //              res = res :+ (mm.message, triple._2, triple._3)
      //          }
      //        }
      //        mm.asker ! TellControlMessage(mm.message, true, self, mm.asker, vectorClock,vectorClock, mm.inspectedTransition, res)
      //        pendingMonitorMessage = pendingMonitorMessage filterNot mm .==
      //      }
    //}
    val foundTransHistory: Vector[(MyTransition, Array[Int], String)] = history.filter(_._1 == transition)
    val foundTransPending: Vector[AskControlMessage] = pendingMonitorMessage.filter(_.inspectedTransition == transition)
    for(mm <- foundTransPending) {
      for (triple <- foundTransHistory) {
        var res: Vector[(MyTransition, Array[Int], String)] = Vector()
        if (triple._3 == "?" && vectorClockConcurent(triple._2, mm.msgVc)) {
          res = res :+ (mm.message, triple._2, "frmP")
        }
        if (triple._3 != "?" && vectorClockNotGreater(triple._2, mm.msgVc)) {
          res = res :+ triple
        }
        //vc should be vectorclock or msgVC
        mm.asker ! TellControlMessage(mm.message, self, mm.asker, vectorClock, mm.msgVc, mm.inspectedTransition, res)
      }
      pendingMonitorMessage = pendingMonitorMessage filterNot mm .==
    }
  }


  def manageAsks(): Receive = {
    case AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked) =>
      updateVectorClock(vc)
      if(unNotified.isEmpty) {
        println("Ask Message " + " " + self.path.name + " " + message.messageBundle.m)
        //val tellControlMessage: TellControlMessage = tellStatusToSender(message.from, message.to, message.messageBundle,message.regTransition,asker)
        val (tellControlMessage,isPending) = tellStatusToSender(AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked))
        if(!isPending)
          sender() ! tellControlMessage
      }
      else {
        val askmsg: AskControlMessage = AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked)
        stashAskQueue = stashAskQueue :+ askmsg
      }
  }

  val manageControls : Receive = {
    case SetAutomata(aut) => {
      automata = aut
    }
    case SendOrderMessage(to, message) => {
      sendSecureMessage(to, NormalMessage(message))
    }
    case ErrorMessage(vc) => {
      updateVectorClock(vc)
      println("Error Message" + " " + self.path.name)
    }

    case NotifyControlMessage(asker,vc) => {
      updateVectorClock(vc)
      println("Notify Message" + " " + self.path.name)
      unNotified = unNotified.filter(_ != asker)
      // i assume that here unnotified just became empty cause we dont get notify message
      // unless we have something in unnotified so if its empty now it's just became empty
      if (unNotified.isEmpty) {
        while (!stashAskQueue.isEmpty) {
          //Here that we have stashed should we change the vector clock of the stashed messages
          //Update: I changed it to be the simpler version and send the old message with the old vc
          self ! stashAskQueue.last
          stashAskQueue = stashAskQueue.init
        }
        while (!stashNormalQueue.isEmpty) {
          self ! stashNormalQueue.last
          stashNormalQueue = stashNormalQueue.init
        }
      }
    }

    case AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked) =>
      updateVectorClock(vc)
      if(unNotified.isEmpty) {
        println("Ask Message " + " " + self.path.name + " " + message.messageBundle.m)
        //val tellControlMessage: TellControlMessage = tellStatusToSender(message.from, message.to, message.messageBundle,message.regTransition,asker)
        val (tellControlMessage,isPending) = tellStatusToSender(AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked))
        if(!isPending)
          sender() ! tellControlMessage
      }
      else {
        val askmsg: AskControlMessage = AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked)
        stashAskQueue = stashAskQueue :+ askmsg
      }

    //stashing ?
    case TellControlMessage(message, teller, dest, vc, msgVC, transition, repRecs) =>
      updateVectorClock(vc)
      println("I CAUGHT A TELL CONTROL MESSAGE")
      val recvTrans = receivedResponse.filterKeys(_._1 == transition)
      val recvTransWithVC = recvTrans.filterKeys(_._2(hash) == msgVC(hash))
      for((inspected, rcvResp) <- recvTransWithVC){
        val newRcvResp:  Vector[(MyTransition, Array[Int], String)] = rcvResp ++ repRecs
        receivedResponse += (inspected -> newRcvResp)
      }
      val pendingTrans = pendingAsk.filterKeys(_._1 == transition)
      val pendingTransWithVC = pendingTrans.filterKeys(_._2(hash) == msgVC(hash))
      for((pending, transList) <- pendingTransWithVC){
        val newTransList : Vector[(MyTransition, Array[Int])] = transList filterNot (message,msgVC).==
        //it should update the pendingAsk map
        pendingAsk += (pending ->  newTransList)
        if (newTransList.length == 0){
          relaxedTellCheck(pending._1, pending._2, false)
        }
      }
    case NormalMessageWithVectorClock(message,vc) =>
      //increment vectorClock
      updateVectorClock(vc)
      if(unNotified.isEmpty) {
        //here we don't send
        vectorClock(hash) += 1
        self ! message
      } else
        stashNormalQueue = stashNormalQueue :+ NormalMessageWithVectorClock(message,vc)
  }

//  val manageNormals: Receive = {
//
//  }

  //user should write it
  def receive = manageControls//.orElse(manageNormals)

  def tellStatusToSender(message: AskControlMessage): (TellControlMessage, Boolean) ={
    var res:  Vector[(MyTransition, Array[Int], String)] = Vector()
    var isPending: Boolean = false
    var answer = true
    answer = false
    val foundTransHistory: Vector[(MyTransition, Array[Int], String)] = history.filter(_._1 == message.message)
    val foundPending: Vector[(MyTransition, Array[Int], String)] = foundTransHistory.filter(_._3 == "?")
    if(foundPending.length != 0){
      for(triple <- foundPending){
        if (vectorClockLess(triple._2, message.msgVc)) {
          pendingMonitorMessage = pendingMonitorMessage :+ message
          isPending = true
        }
      }
    }
//    for(triple <- history){
//      if(triple._1 == message.message){
//        if(triple._3 == "?") {
//          if (vectorClockLess(triple._2, message.msgVc)) {
//            //what to do with this?
//            //pendingMonitorMessage = pendingMonitorMessage :+ (message, triple._1)
//            pendingMonitorMessage = pendingMonitorMessage :+ message
//            isPending = true
//          }
//        }
//      }
//    }
    if(isPending == false){
      for(triple <- foundTransHistory){
        if(triple._3 == "?" && vectorClockConcurent(triple._2, message.msgVc)){
          res = res :+ (message.message, triple._2, "frmP")
        }
        if(triple._3 != "?" && vectorClockNotGreater(triple._2, message.msgVc)){
          res = res :+ triple
        }
      }
    }
//    if(isPending == false){
//      for(triple <- history){
//        if(triple._1 == message.message){
//          if(triple._3 == "?"){
//            if(vectorClockConcurent(triple._2, message.msgVc))
//              res = res :+ (message.message, triple._2, "frmP")
//          }
//          else if(vectorClockNotGreater(triple._2, message.msgVc))
//            res = res :+ (message.message, triple._2, triple._3)
//        }
//      }
//    }
    if (!isPending) {
      if (message.isBlocked)
        unNotified = unNotified :+ message.asker
    }
    //TODO check history and automata, send a tell message to sender with msg and true/false, sender will erase that msg from his "Pres set"
    //change vectorClock to ask's vectorclock
    (TellControlMessage(message.message, self, message.asker, vectorClock,message.msgVc, message.inspectedTransition, res), isPending)
  }
}

  //  def tellStatusToSender(from: Int, to: Int, messageBundle: MessageBundle, regTransiton: Boolean, asker: ActorRef): TellControlMessage ={
//          var answer = true
//          val transition: MyTransition = MyTransition(from, to, messageBundle, regTransiton)
//          answer = false
//          for(triple <- history){
//            if(triple._1 == transition){
//              answer = true
//            }
//          }
//          val tellControlMessage: TellControlMessage = TellControlMessage(transition, answer, vectorClock)
//          //TODO check history and automata, send a tell message to sender with msg and true/false, sender will erase that msg from his "Pres set"
//          //assumed that tell does just like !
//          println(tellControlMessage)
//          // could be asker ! tellControlMessage
//          unNotified = unNotified :+ asker
//          tellControlMessage
//  }
//}


object MainApp extends App {
  import SecureActor._
  val system: ActorSystem = ActorSystem("helloAkka")
  val firstActor: ActorRef =
    system.actorOf(SecureActor.props().withDispatcher("custom-dispatcher"),"firstActor")
  val secondActor: ActorRef =
    system.actorOf(SecureActor.props().withDispatcher("custom-dispatcher"),"secondActor")
  val customAutomata: Automata = new Automata
  val customBundle: MessageBundle = new MessageBundle(secondActor, "a0", firstActor)
  val customTransition: MyTransition = MyTransition(0,1, customBundle ,true)
  val customBundle2: MessageBundle = new MessageBundle(firstActor, "b1",secondActor)
  val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
  customAutomata.addTransition(customTransition)
  customAutomata.addTransition(customTransition2)
  customAutomata.addLastTransition(2)
  secondActor ! SetAutomata(customAutomata)
  firstActor ! SetAutomata(customAutomata)
  secondActor ! SendOrderMessage(firstActor, "a0")
  secondActor ! SendOrderMessage(secondActor, "c2")
  firstActor ! SendOrderMessage(secondActor, "b1")
  secondActor ! SendOrderMessage(secondActor, "c1")
}
