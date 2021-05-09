# secured-akka
A monitoring algorithm for Akka applications. After importing `secured-akka` you are able to send messages securely via sending the `SendSecureMessage` to the sender Actor.

## Getting Started
### Prerequisites
```sh
scala
jdk
sbt
```
First, export the project to a JAR file. You can use the `build artifacts` section of *IntelliJ* and access the JAR file in the *out* folder in the project directory.
Copy the JAR file to your Akka application directory called lib. After that, import the JAR file in your Akka application. In IntelliJ, you can import it via `Project Structure -> Modules -> Dependencies -> +`.


### Writing Your Akka Application
After importing the project you can write an Akka application using Classic Actors version. The documentation is available [here](https://doc.akka.io/docs/akka/current/actors.html). 
To use the algorithm you have to do the following:
* Extending Secure Actor class:
`class D extends SecureActor`
* Defining the `Receive` behavior, integrating it with the receive of monitoring messages. <br/>
```def receive = manageControls.orElse(manageMessages)``` <br/>
 you can define your own receive behavior by writing a variable.
``` val manageMessages: Receive = {}```

* Calling the `sendSecure` function within an actor or sendin the `SendOrderMessage` outside an actor for sending a message.

``` sendSecureMessage(receiver, msg)``` <br/>
 <br/>
``` sender ! SendOrderMessage(receiver, msg)``` <br/>

* `dfa.json` file, describing the automata in JSON format. The file should be in your project's most outer directory.

## Contribution
Please send an email to [me](mailto:sina.nabavi16@gmail.com).

    

## Authors

* [**Sina Nabavi**](https://github.com/PhantomBleak) &emsp;&emsp;*Develop, Review, Test*
