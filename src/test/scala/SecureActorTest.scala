package SyncUnitTest
import akka.actor.AbstractActor.Receive
import com.{Automata, MessageBundle, MyTransition, SecureActor}
import akka.actor.{ActorPath, ActorRef, ActorSystem, Props}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.SecureActor.{AskControlMessage, ErrorMessage, NormalMessage, NormalMessageWithVectorClock, StashedNormalMessage, TellControlMessage}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._


class SecureActorTest
  extends TestKit(ActorSystem("MethodsUnitTests", ConfigFactory.parseString(TestKitUsageSpec.config)))
  with DefaultTimeout
  with ImplicitSender
  with AnyWordSpecLike
  with Matchers
  with BeforeAndAfterAll{


  "message ordering" should {
    "prioritize stashed over control and control over normal" in {
      val customBundle: MessageBundle = new MessageBundle("secondActor", "a0", "firstActor")
      val customTransition: MyTransition = MyTransition(0, 1, customBundle, true)
      val customBundle2: MessageBundle = new MessageBundle("firstActor", "b1", "secondActor")
      val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
      val actorB = TestProbe()
      val actorRefA: TestActorRef[SecureActor] = TestActorRef(SecureActor.props().withDispatcher("custom-dispatcher"), "firstActor")
      val actorA = actorRefA.underlyingActor
      val actorRefB: TestActorRef[SecureActor] = TestActorRef(Props(new SecureActor() {
        val manageError : Receive = {case msg => actorB.ref ! msg }
        override def receive = manageError.orElse(manageControls)
      }).withDispatcher("custom-dispatcher"), "secondActor")
      actorRefB ! NormalMessage("test")
      actorRefB ! AskControlMessage(customTransition, actorRefA , actorA.vectorClock, customTransition, true)
      actorRefB ! StashedNormalMessage(NormalMessageWithVectorClock("test", actorA.vectorClock))
      actorB.expectMsgPF(){
        case NormalMessage(msg) =>
      }
      actorB.expectMsgPF(){
        case StashedNormalMessage(normalMessageWithVectorClock) =>
      }
      actorB.expectMsgPF(){
        case AskControlMessage(tr, actorRefA , vc , trInspccted, regTrans) =>
      }
    }
  }
  "relaxed tell check blocking error" should {
    "go into second if and update history and send error" in {
      val customBundle: MessageBundle = new MessageBundle("secondActor", "a0", "firstActor")
      val customTransition: MyTransition = MyTransition(0, 1, customBundle, true)
      val customBundle2: MessageBundle = new MessageBundle("firstActor", "b1", "secondActor")
      val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
      val actorB = TestProbe()
      val actorRefA: TestActorRef[SecureActor] = TestActorRef(SecureActor.props().withDispatcher("custom-dispatcher"), "firstActor")
      val actorRefB: TestActorRef[SecureActor] = TestActorRef(Props(new SecureActor() {
        val manageError : Receive = {case ErrorMessage(vc) => actorB.ref ! ErrorMessage(vc) }
        override def receive = manageError.orElse(manageControls)
      }).withDispatcher("custom-dispatcher"), "secondActor")
      val actorA = actorRefA.underlyingActor
      actorA.receivedResponse += ((customTransition2, actorA.vectorClock) -> Vector[(MyTransition, Array[Int], String)]())
      var resRep:  Vector[(MyTransition, Array[Int], String)] = Vector[(MyTransition, Array[Int], String)]()
      val vc :Array[Int] = actorA.vectorClock.clone()
      resRep = resRep :+ (customTransition, vc, "frm")
      actorA.receivedResponse += (customTransition2, actorA.vectorClock) -> resRep
      actorA.relaxedTellCheck(customTransition2, actorA.vectorClock, true)
      assert(actorA.history.contains((customTransition2, actorA.vectorClock, "frmP")))
      actorB.expectMsgPF(){
        case ErrorMessage(vc) => assert(vc.equals(actorA.vectorClock))
      }
    }
  }

  "relaxed tell check blocking error" should {
    "go into first if and update history and send error" in {
      val customBundle: MessageBundle = new MessageBundle("secondActor", "a0", "firstActor")
      val customTransition: MyTransition = MyTransition(0, 1, customBundle, true)
      val customBundle2: MessageBundle = new MessageBundle("firstActor", "b1", "secondActor")
      val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
      val actorB = TestProbe()
      val actorRefA: TestActorRef[SecureActor] = TestActorRef(SecureActor.props().withDispatcher("custom-dispatcher"), "firstActor")
      val actorRefB: TestActorRef[SecureActor] = TestActorRef(Props(new SecureActor() {
        val manageError : Receive = {case ErrorMessage(vc) => actorB.ref ! ErrorMessage(vc) }
        override def receive = manageError.orElse(manageControls)
      }).withDispatcher("custom-dispatcher"), "secondActor")
      val actorA = actorRefA.underlyingActor
      actorA.receivedResponse += ((customTransition2, actorA.vectorClock) -> Vector[(MyTransition, Array[Int], String)]())
      var resRep:  Vector[(MyTransition, Array[Int], String)] = Vector[(MyTransition, Array[Int], String)]()
      val vc :Array[Int] = actorA.vectorClock.clone()
      vc(actorA.hash) = vc(actorA.hash) - 10
      resRep = resRep :+ (customTransition, vc, "frm")
      actorA.receivedResponse += (customTransition2, actorA.vectorClock) -> resRep
      actorA.relaxedTellCheck(customTransition2, actorA.vectorClock, true)
      assert(actorA.history.contains((customTransition2, actorA.vectorClock, "frm")))
      actorB.expectMsgPF(){
        case ErrorMessage(vc) => assert(vc.equals(actorA.vectorClock))
      }
    }
  }

  "tell status to sender pending" should {
    "not add to res data structure and not add asker to unnotified" in {
      val customBundle: MessageBundle = new MessageBundle("secondActor", "a0", "firstActor")
      val customTransition: MyTransition = MyTransition(0,1, customBundle ,true)
      val customBundle2: MessageBundle = new MessageBundle( "firstActor" , "b1","secondActor")
      val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
      val actorRefB: TestActorRef[SecureActor] = TestActorRef(SecureActor.props().withDispatcher("custom-dispatcher"), "secondActor")
      val actorA = TestProbe()
      val actorB = actorRefB.underlyingActor
      val askMsgVc: Array[Int] = actorB.vectorClock.clone()
      val askMsg = AskControlMessage(customTransition2, actorA.ref, askMsgVc, customTransition, false)
      actorB.history = actorB.history :+ (customTransition2, actorB.vectorClock, "?")
      val (tellMsg, isPending) = actorB.tellStatusToSender(askMsg)
      assert(actorB.pendingMonitorMessage.contains(askMsg))
      assert(isPending)
      assert(tellMsg.repRecs.isEmpty)
      assert(actorB.unNotified.isEmpty)
    }
  }

  "handle pending" should {
    "remove pending messages properly from pending dataset" in {
      val customBundle: MessageBundle = new MessageBundle("secondActor", "a0", "firstActor")
      val customTransition: MyTransition = MyTransition(0,1, customBundle ,true)
      val customBundle2: MessageBundle = new MessageBundle( "firstActor" , "b1","secondActor")
      val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
      val actorRefB: TestActorRef[SecureActor] = TestActorRef(SecureActor.props().withDispatcher("custom-dispatcher"), "secondActor")
      val actorA = TestProbe()
      val actorB = actorRefB.underlyingActor
      val askMsg = AskControlMessage(customTransition2, actorA.ref, actorB.vectorClock, customTransition, isBlocked = false)
      actorB.pendingMonitorMessage = actorB.pendingMonitorMessage :+ askMsg
      assert(actorB.pendingMonitorMessage.length == 1)
      actorB.history = actorB.history :+ (customTransition, actorB.vectorClock, "?")
      actorB.handlePending(customTransition)
      assert(actorB.pendingMonitorMessage.isEmpty)
      actorA.expectMsgPF(){
        case TellControlMessage(message, teller, dest, vc, msgVC, inspectedTransition, repRecs) => assert(repRecs(0)._3 == "frmP")
      }
    }
  }
}

object TestKitUsageSpec {
  // Define your test specific configuration here
  val config = """
    akka {
      loglevel = "WARNING"
    }
    custom-dispatcher {
      mailbox-requirement =
        "com.MyControlAwareMessageQueueSemantics"
    }
    akka.actor.mailbox.requirements {
      "com.MyControlAwareMessageQueueSemantics" =
        custom-dispatcher-mailbox
    }

    custom-dispatcher-mailbox {
      mailbox-type = "com.MyControlAwareMailbox"
    }
    """


}