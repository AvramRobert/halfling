# halfling

A simplistic Clojure library for creating, manipulating and composing asynchronous actions, that
is built atop Clojure's existing support for futures. 

Two of the main things that futures in Clojure lack are composability, and a certain
degree of laziness. This library attempts to provide these characteristics, plus some additional
tooling for working with them.

## Usage     
The main abstraction in halfling is something called a `Task`. 
`Task` is essentially a wrapper around Clojure's `future`.

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
Right, so by now nothing actually happened. Tasks are lazy by default and
every operation you perform on them (aside from execution) is also computed lazily. 
In order to get the value of a task, you have to explicitly run it. This can be done either 
synchronously or asynchronously.

* Synchronous execution: <br />
```Clojure
> (t/run adding)
=> #halfling.result.Result{:status :success, :val 2}
```
Running a task synchronously returns something called a `Result`. `Result` is a record, that
represents the outcome of an execution as data. They can either be successful and contain the 
value of some computation; or failed, and contain information about their cause, message and stack-trace. 
Results themselves are also composable. 

* Asynchronous execution: <br />
```Clojure
> (def added (t/run-async adding))
=> #'user/added

> (t/executed? added)
=> true
```
Running a task asynchronously doesn't return a `Result`, but rather another task.
This task contains a promise, which will receive the result of the
computation once it's completed. 
  
<b>Note:</b> `wait` can be used to block and wait on an asynchronous execution. 
This will then also extract the `Result` from the task.

#### Composing tasks
Tasks can be composed by using the `then` primitive. This takes a
task and some sort of callback function, and returns a new task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then inc)
                         (t/then dec)))
=> #'user/crucial-maths

> (t/run crucial-maths)
=> #halfling.result.Result{:status :success, :val 2}
```
Additionally, the callback function can either return a simple value or
another task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then #(t/task (inc %)))
                         (t/then dec)))
=> #'user/crucial-maths

> (t/run crucial-maths)
=> #halfling.result.Result{:status :success, :val 2}
```
By the magic of referential transparency, this leads
to the same outcome as before. 

#### Asynchronous composition
The way tasks are meant to be executed, however, is asynchronously.
In comparison to synchronous execution, tasks executed asynchronously
maintain their composability, as they return new tasks instead of direct results:
```Clojure
> (def crucial-math (-> (t/task (+ 1 1))
                        (t/then #(t/task (inc %)))
                        (t/run-async)
                        (t/then dec)))
=> #'user/crucial-math

> (t/wait crucial-math)
=> #halfling.result.Result{:status :success, :val 3}
```
<b>Wait</b>, but this isn't the same result as before. The previous result was
2, now it's 3. That's because `run-async` only executes those tasks that came before its invocation. 
If additional compositions are made while it's executing, then these shall remain unexecuted until another 
call to either `run-async` or `run` is made. This is due to its lazy character: 
```Clojure
> (def crucial-math (-> (t/task (+ 1 1))
                        (t/then #(t/task (inc %)))
                        (t/run-async) ;; <- (inc (+ 1 1))
                        (t/then dec))) ;; <- unexecuted
=> #'user/crucial-math
```
By calling `run` (or alternatively `run-async` again), the subsequent operations are also run and
the complete result is returned:
```Clojure
> (t/run crucial-math)
=> #halfling.result.Result{:status :success, :val 2}

```
 
#### Task contexts
Whilst threading tasks from one to the other looks
pretty, it isn't really that appropriate when performing
asynchronous actions, that do not flow linearly. 
For this there is `do-tasks`: 
```Clojure
> (def crucial-maths 
      (t/do-tasks [a (t/task (+ 1 1))
                   b1 (t/task (inc a))
                   b2 (dec a)]
                  (+ a (- b1 b2))))
=> #'user/crucial-maths

> (t/run crucial-maths)
=> #halfling.result.Result{:status :success, :val 4}
```
With this, you can use binding-forms to treat asynchronous
values as if they were realized, and use them in that local context.
`do-tasks` accepts both simple values and other tasks. It automatically "promotes"
simple values to tasks in order to work with them. <b>Note:</b> `do-tasks` essentially
desugars to nested `then`-calls, which means that the binding-forms are <i>serialised</i>. 

#### Failures
Previously I stated, that task results can either be successful or failed. In the later case,
the general rule is that once a task has failed, this failure get's propagated
and its execution stops at that point. In case of a failure, `Result`
will contain information about it:
```Clojure
> (def failed (-> (t/task (+ 1 1))
                  (t/then inc)
                  (t/then (fn [_] (throw (new Exception "HA"))))
                  (t/then dec)))
=> #'user/failed

> (t/runderef-task failed)
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
doing certain operations with them. These can be found in their respective namespaces.
<br />
<br />
Now, for the big question. Why not use `manifold` or `imminent` for this sort of thing?
Well.. you probably should. Both are more extensive in the things you can do with them. 
However, the main characteristics that differentiate this library from those are simplicity and semantics.
halfling builds upon what Clojure already provides and simply extends their capacity.
It neither implements its own execution framework, nor does it somehow try to be a replacement
for Clojure's already existing future support. You can simply consider it a plug-in of sorts for
what Clojure already has. 
## License

Copyright © 2017 Robert Marius Avram

Distributed under the MIT License.
