# Activities

<head>
  <meta charset="UTF-8" />
  <meta name="description" content="ZIO Temporal activities" />
  <meta name="keywords" content="ZIO Temporal activities, Scala Temporal activities" />
</head>


## Introduction

One of the primary things that Workflows do is orchestrate the execution of Activities.  
While Workflows are deterministic (e.g. without side effects), activities are normal functions/methods that may interact
with the real world.  
Refer to [Temporal documentation](https://docs.temporal.io/activities/) for more information regarding the concept of
activities.

## Defining activities

Let's start with some basic imports that will be required for the whole demonstration:

```scala mdoc:silent
import zio._
import zio.temporal._
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.temporal.activity._

import java.util.UUID
```

A simple activity definition consists of the following parts:

- A Scala *trait* with one or more abstract methods
- The *trait* itself should be annotated with `@activityInterface`
- Methods could be optionally annotated with `@activityMethod`

```scala mdoc
@activityInterface
trait BookingActivity {
  def bookFlight(name: String, surname: String, flightNumber: String): UUID /*Booking ID*/ 
  
  def purchaseFlight(bookingId: UUID, cardId: UUID): UUID /*Booking ID*/ 
}
```

Implementing simple workflows is as simple as just plain interfaces:

```scala mdoc
class BookingActivityImpl extends BookingActivity {
  override def bookFlight(name: String, surname: String, flightNumber: String): UUID = {
    println(s"Booking flight no #$flightNumber for $name $surname")
    UUID.randomUUID()
  }
  
  override def purchaseFlight(bookingId: UUID, cardId: UUID): UUID = {
    println(s"Purchased the booking $bookingId")
    bookingId
  }
}
```

`ZIO Temporal` also provides you an option to run arbitrary `ZIO` code inside the activity!

```scala mdoc
class BookingActivityZIOImpl(implicit options: ZActivityOptions[Any]) extends BookingActivity {
  override def bookFlight(name: String, surname: String, flightNumber: String): UUID = {
    ZActivity.run {
      for {
        _ <- ZIO.log(s"Booking flight no #$flightNumber for $name $surname")
        bookingId <- ZIO.randomWith(_.nextUUID)
      } yield bookingId
    }
  }
  
  override def purchaseFlight(bookingId: UUID, cardId: UUID): UUID = {
    ZActivity.run {
      for {
        _ <- ZIO.log(s"Purchased the booking $bookingId")
      } yield bookingId
    }
  }
}
```

Important notes regarding `ZIO` example:
- In order to run `ZIO` inside the activity, it's required for the activity to have an implicit `ZActivityOptions` available.  
- `ZActivityOptions[+R]` contains the following:
  - `zio.Runtime[+R]` instance allowing to run the `ZIO` itself
  - `ActivityCompletionClient` instance which allows to complete the Activity asynchronously
  - Those, the Activity method execution won't block on the worker, allowing to utilize resources better.

## Using activities inside workflows

Using activities is pretty straightforward: you could invoke activity methods inside your workflows just as plain Scala functions.  
The only difference is how you create activities.  

Let's start by defining a simple workflow:

```scala mdoc
@workflowInterface
trait BookingWorkflow {
  @workflowMethod
  def bookFlight(name: String, surname: String, flightNumber: String, cardId: UUID): UUID /*Booking ID*/
}
```

Then you implement it the same way as usual:

```scala mdoc
class BookingWorkflowImpl extends BookingWorkflow {
  private val bookingActivity: ZActivityStub.Of[BookingActivity] = ZWorkflow
    .newActivityStub[BookingActivity]
    .withStartToCloseTimeout(10.seconds)
    .build
    
  override def bookFlight(name: String, surname: String, flightNumber: String, cardId: UUID): UUID = {
    val bookingId = ZActivityStub.execute(
      bookingActivity.bookFlight(name, surname, flightNumber)
    )
    bookingActivity.purchaseFlight(bookingId, cardId)
  }
}
```

Unlike usual composition with constructor parameters, Temporal requires you to create dependencies via its library API.  
In this case, we create an instance of `ZActivityStub.Of[BookingActivity]` via `ZWorkflow.newActivityStub` method. 
The method accepts the Activity **interface** type (but **not the implementation**).  
It's also mandatory to provide at least the `start to close timeout`, which we'll cover later.  

Important notes:
- `ZWorkflow.newActivityStub` provides you a stub which communicates to Temporal cluster in order to invoke activities
- **You must always** wrap the activity method invocation into `ZActivityStub.execute` method.
  - The `ZActivityStub.Of[BookingActivity]` is a compile-time stub, so actual method invocations are only valid in compile-time
  - `bookingActivity.bookFlight(name, surname, flightNumber)` invocation would be re-written into an untyped Temporal's activity invocation
    (see [activity Execution doc](https://docs.temporal.io/application-development/foundations?lang=java#activity-execution))
  - A direct method invocation will throw an exception
- The `ZActivityStub` is basically a proxy, which executes the Activity (via Temporal cluster or locally in case of `newLocalActivityStub`)
- Activity method invocation result is persisted into a journal (e.g. database like Postgres etc.)
- Persisting the result allows the workflow to retry in case of any failures, starting from the closest successful activity invocation
- We'll cover retries later

**NOTE**: Do not annotate activity stubs with the activty interface type. It must be `ZActivityStub.Of[BookingActivity]`.  
Otherwise, you'll get a compile-time error:

```scala mdoc:fail
def doSomething(bookingActivity: BookingActivity): UUID =
  ZActivityStub.execute(bookingActivity.bookFlight("John", "Doe", "4242"))
```

## Adding activities into the worker

Providing activity implementations is done when defining the worker instance.
It's pretty easy to provide the implementation: it could be done via `ZIO`'s standard dependency injection mechanism:

```scala mdoc:silent
val worker: URLayer[BookingActivity with ZWorkerFactory, Unit] =
  ZLayer.fromZIO {
    ZWorkerFactory.newWorker("booking") @@
      ZWorker.addActivityImplementationService[BookingActivity] @@
      ZWorker.addWorkflow[BookingWorkflow].from(new BookingWorkflowImpl)
  }.unit
```

The activity could be created as follows:

```scala mdoc:silent
val activityLayer: URLayer[ZActivityOptions[Any], BookingActivity] =
  ZLayer.fromFunction(new BookingActivityZIOImpl()(_: ZActivityOptions[Any]))
```

... and then somewhere you build your program:

```scala
val program = ???

program.provide(
  // ... All the temporal dependencies
  ZActivityOptions.default, // default ZActivityOptions
  activityLayer // The activity implementation
)
```