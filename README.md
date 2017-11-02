# halfling
![](resources/intro-image.jpg)
<br/>
A simplistic Clojure library for creating, manipulating and composing asynchronous actions, that
is built atop Clojure's existing support for futures.

Two of the main things that futures in Clojure lack are composability and a certain
degree of laziness. This library attempts to provide these characteristics, plus some additional
tools for working with them.

## Clojars
<b>A word of caution:</b> this library is in its infancy.  <br />
[![Clojars Project](https://img.shields.io/clojars/v/halfling.svg)](https://clojars.org/halfling)
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
In order to make a task do something, you have to explicitly run it. This can be done either
synchronously or asynchronously.

The invariant is that running a task will always return another task, which contains the result of that execution.
This can be subsequently manipulated and composed with other tasks.


#### Synchronous execution
```Clojure
> (t/run adding)
=> #object[halfling.task.Task 0x5bbeab6c "halfling.task.Task@5bbeab6c"]
```
This type of execution will naturally block the current thread until the tasks finishes.

#### Asynchronous execution
```Clojure
> (def added (t/run-async adding))
=> #'user/added

> (t/executed? added)
=> true
```
Running a task asynchronously will not block the current thread and return immediately.
The task itself captures a promise which will eventually be filled with the result of that execution.

<b>Note:</b> `wait` can be used to block and wait on an asynchronous execution.

#### Task values
In some cases, you may want to retrieve the actual inner value of a task.
This can be achieved with `get!`. `get!` can return one of two things:

* If a task succeeded, it will return the concrete value of an execution:
```clojure
> (t/get! added)
=> 2
```
* If a task failed, it will return a map containing a failure message and a possible stack trace:
```clojure
{:message <some string message>
 :trace   <vector of stack elements>
```

There's a separate `get-or-else` function, which will return the value in
case of a success, or a provided `else` alternative in case of failure:
```clojure
> (t/get-or-else (t/run (t/task 1)) -1)
=> 1

> (-> (t/task (throw (Exception. "Nope")))
      (t/run)
      (t/get-or-else -1))
=> -1
```

#### Task results
Every task actually wraps something called a `Result`, which indicates the outcome
of an execution. Every `Result` has the following structure:

* In case of successful execution:
```clojure
{:status :success
 :value  <result of execution>}
```
* In case of failed execution:
```clojure
{:status :failed
 :value  {:message <string error message>
          :trace   <possible vector of stacktrace elements>}
 }
```
If you desire to actually look at the result of execution, you may do so with `peer`:
```clojure
> (-> (t/task 1)
      (t/run)
      (t/peer))

=> #halfling.task.Result{:status :success, :value 1}
```
`get!` actually extracts the `:value` of a `Result`.

#### Composing tasks
Tasks can be composed by using the `then` primitive. This takes a
task and some sort of callback function, and returns a new task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then inc)
                         (t/then dec)))
=> #'user/crucial-maths

> (-> (t/run crucial-maths)
      (t/get-or-else 0))
=> 2
```
Additionally, the callback function can either return a simple value or another task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then #(t/task (inc %)))
                         (t/then dec)))
=> #'user/crucial-maths

> (-> (t/run crucial-maths)
      (t/get-or-else 0))
=> 2
```
By the magic of referential transparency, this leads to the same outcome as before.

##### Composition after execution
Tasks maintain composability after execution. They return other tasks which contain
the result of those executions. However, because they are computed lazily, it means that if you've
executed a task and composed new things into it, you'll have to execute it again in order to force the compositions.

Example:
```Clojure
> (def crucial-math (-> (t/task (+ 1 1))
                        (t/then #(t/task (inc %)))
                        (t/run-async) ;; <- (inc (+ 1 1))
                        (t/then dec))) ;; <- unexecuted
=> #'user/crucial-math
```
`run-async` will only execute those tasks that came before its invocation.
If additional compositions are made while it's executing, these shall remain un-executed until another
call to either `run-async` or `run` is made:
```Clojure
> (r/get! (t/run crucial-math))
=> 2
```

##### Chaining effects
You can chain task effects by using the `then-do` macro.
`then-do` sequentially composes effects into one task:
```clojure
> (-> (t/task (println "Launching missiles!"))
      (t/then-do (println "Missiles launched!"))
      (t/then-do (println "Death is imminent!"))
      (t/run)
      (t/get!)

Launching missiles!
Missiles launched!
Death is imminent!
=> nil

```

#### Task comprehension
Whilst threading tasks from one to the other looks
pretty, it isn't really that appropriate when working with
interdependent tasks. For this there is `do-tasks`:
```Clojure
> (def crucial-maths 
      (t/do-tasks [a (t/task (+ 1 1))
                   b1 (t/task (inc a))
                   b2 (dec a)]
                  (+ a (- b1 b2))))
=> #'user/crucial-maths

> (-> (t/run crucial-maths) (r/get!))
=> 4
```
With this, you can use binding-forms to treat task
values as if they were realized, and use them in that local context.
`do-tasks` accepts both simple values and other tasks. It automatically "promotes"
simple values to tasks in order to work with them. <b>Note:</b> `do-tasks` essentially
desugars to nested `then`-calls, which means that the binding-forms are <i>serialised</i>. 

#### Parallelism
Halfing supports parallel execution with the functions `mapply`, `zip` and
`sequenced` (see `halfing.task`). Additionally, there is also a `p-map` implementation available (see `halfling.lib`),
which uses the task API. This, similar to Clojure's `pmap`, should only be used when the computation
performed outweighs the distribution overhead. An example usage: 
```clojure
> (require '[halfling.lib :refer [p-map])
=> nil

>(def alph (vec (flatten
                    [(take 26 (iterate #(-> % int inc char) \A))
                     (take 26 (iterate #(-> % int inc char) \a))])))
=> #'user/alph

> (defn rand-str [n]
   (apply str (map (fn [_] (rand-nth alph)) (range 0 n))))
=> #'user/rand-str

> (defn strings [amount length]
   (map (fn [_] (rand-str length)) (range 0 amount)))
=> #'user/strings

> (def work (p-map clojure.string/lower-case (strings 4000 1000)))
=> #'user/work

; (t/run work) or (t/run-async work)

(time (do (t/run work) ()))
"Elapsed time: 8.076544 msecs"
=> ()
```

#### Final thoughts
Now, for the big question. Why not use [manifold](https://github.com/ztellman/manifold) or [imminent](https://github.com/leonardoborges/imminent) for this sort of thing?
Well.. you probably should. Both are more extensive in the things you can do with them. 
However, the main characteristics that differentiate this library from those are simplicity and semantics.
halfling builds upon what Clojure already provides and simply extends their capacity.
It neither implements its own execution framework, nor does it somehow try to be a replacement
for Clojure's already existing future support. You can simply consider it a plug-in of sorts for
what Clojure already has. 
## License

Copyright © 2017 Robert Marius Avram

Distributed under the MIT License.
