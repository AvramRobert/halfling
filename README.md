# halfling

A simplistic Clojure library for creating, manipulating and composing asynchronous actions, that
is built atop Clojure's existing support for futures. 

Two of the main things that futures in Clojure lack are composability, and a certain
degree of laziness. This library attempts to provide these characteristics, plus some additional
tooling for working with them.

## Usage     
The main abstraction in halfling is something called a `Task`. 
`Task` is essentially a wrapper around Clojure's `future`, that is <i>lazy</i> by default.

#### Tasks
Let's create some tasks: <br />
```Clojure
> (require '[halfling.task :as t])
=> nil

> (def adding (t/task (+ 1 1)))
=> #'user/adding

> (def no-please (t/task 
                 (Thread/sleep Integer/MAX_VALUE)
                 42))
=> #'user/no-please

> (t/executed? adding)
=> false

> (t/executed? no-please)
=> false

```
Right, so by now nothing actually happened. Tasks are lazy and
every operation you perform on them (aside from execution) is also computed lazily. 
In order to get the value of a task, you have to explicitly run it. This can be done either 
synchronously (blocking), or asynchronously (non-blocking).
* Asynchronous execution: <br />
```Clojure
> (def added (t/run-task adding))
=> #'user/added

> (t/executed? added)
=> true
```
  Running a task asynchronously actually returns another task.
  This task however contains a promise, which will receive the value of the
  computation once it's completed. 
  
* Synchronous execution (dereferencing): <br />
```Clojure
> (t/deref-task adding)
=> #halfling.result.Result{:status :success, :val 2}
```
Running a task synchronously doesn't return another task, 
but rather dereferences it to something called a `Result`. `Result` is a structure
that explicitly represents the outcome of an execution as a value. 
Results can either be successful and contain the value of some
computation; or failed, and contain information about their cause, message and stack-trace. 
Results themselves are also composable. 

<b>Note:</b> `deref-task` can be used to block and wait on an asynchronous execution. 

#### Composing tasks
Tasks can be composed by using the `then` primitive. This takes a
task and some sort of callback function, and returns a new task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then inc)
                         (t/then dec)))
=> #'user/crucial-maths

> (t/deref-task crucial-maths)
=> #halfling.result.Result{:status :success, :val 2}
```
Additionally, the callback function can either return a simple value or
another task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then #(t/task (inc %)))
                         (t/then dec)))
=> #'user/crucial-maths

> (t/deref-task crucial-maths)
=> #halfling.result.Result{:status :success, :val 2}
```
By the magic of referential transparency, this leads
to the same outcome as before. 

#### Asynchronous composition
The way tasks are meant to be executed, however, is asynchronously.
In comparison to synchronous execution, tasks executed asynchronously
maintain their composability: 
```Clojure
> (def crucial-math (-> (t/task (+ 1 1))
                        (t/then #(t/task (inc %)))
                        (t/run-task)
                        (t/then dec)))
=> #'user/crucial-math

> (t/deref-task crucial-math)
=> #halfling.result.Result{:status :success, :val 2}
```
And again, the magic of referential transparency guarantees
the same outcome. <b>Note</b>: `run-task` only executes those tasks that came before its invocation. If additional tasks are composed with it while executing, these
shall remain unexecuted until another call to either `run-task` or `deref-task` is made. 
For example: 
```Clojure
> (def crucial-math (-> (t/task (+ 1 1))
                        (t/then #(t/task (inc %)))
                        (t/run-task) ;; <- (inc (+ 1 1))
                        (t/then dec))) ;; <- unexecuted
=> #'user/crucial-math
```
 
#### Task contexts
Whilst threading tasks from one to the other looks
pretty, it is not really that appropriate when performing
asynchronous actions, that do not flow linearly. 
Let me introduce `do-tasks`: 
```Clojure
> (def crucial-maths 
      (t/do-tasks [a (t/task (+ 1 1))
                   b1 (t/task (inc a))
                   b2 (dec a)]
                  (+ a (- b1 b2))))
=> #'user/crucial-maths

> (t/deref-task crucial-maths)
=> #halfling.result.Result{:status :success, :val 4}
```
With this, you can use the binding-forms to treat asynchronous
values as if they were realized, and use them in other computations.
`do-tasks` accepts both simple values and other tasks. It automatically "promotes"
simple values to tasks in order to work with them. <b>Note:</b> `do-tasks` essentially
desugars to nested `then`-calls, which means that the binding-forms are <i>serialised</i>. 

#### Failures
I said previously that the synchronous execution of a task
dereferences it to a `Result`. In actuality, any execution of a task
leads to a result. With `deref-task` however, you
actually request to see it. The general rule is that once a task has failed, this failure get's propagated
and its execution stops at that point. In case of a failure, `Result`
will contain information about it:
```Clojure
> (def failed (-> (t/task (+ 1 1))
                  (t/then inc)
                  (t/then (fn [_] (throw (new Exception "HA"))))
                  (t/then dec)))
=> #'user/failed

> (t/deref-task failed)
=> #halfling.result.Result{:status :failure,
                           :val {:cause nil,
                                 :message "HA",
                                 :trace [[user$fn__10421 invokeStatic "form-init2102788460686826432.clj" 3]
                                        [user$fn__10421 invoke "form-init2102788460686826432.clj" 3]
                                        [halfling.task$deref_task$fn__1148 invoke "task.clj" 81]
                                        [halfling.task$deref_task invokeStatic "task.clj" 81]
                                        [halfling.task$deref_task invoke "task.clj" 66]
                                        [user$eval10465 invokeStatic "form-init2102788460686826432.clj" 1]
                                        [user$eval10465 invoke "form-init2102788460686826432.clj" 1]
                                        [clojure.lang.Compiler eval "Compiler.java" 6927]
                                        ...}}
```
#### Final thoughts
Both `Task` and `Result` have a number of combinators, that are useful for
doing certain operations. These can be found in their respective namespaces.
<br />
<br />
Now, for the big question. Why not use `manifold` or `imminent` for this sort of thing?
Well.. you probably should. Both are more extensive in the things you can do with them. 
However, the main things that differentiate this library from those are simplicity and semantics.
halfling builds upon what clojure already provides and simply extends their capacity.
It neither implements its own execution framework, nor does it try to be a replacement
for Clojure's already existing future support. You can simply consider it a plug-in of sorts for
what Clojure already has. 
## License

Copyright © 2017 Robert Marius Avram

Distributed under the MIT License.
