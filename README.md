# halfling
![](resources/intro-image.jpg)
<br/>
A simplistic Clojure library for creating, manipulating and composing asynchronous actions, that
is built atop Clojure's existing support for futures.

Two of the main things that futures in Clojure lack are composability and a certain
degree of laziness. This library attempts to provide these characteristics, plus some additional
tools for working with them.

## Clojars
[![Clojars Project](https://img.shields.io/clojars/v/halfling.svg)](https://clojars.org/halfling)
## Usage     
The main abstraction in halfling is something called a `Task`. <br />
`Task` is essentially a fancy wrapper around Clojure's `future`.

### Tasks
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

### Synchronous execution
```Clojure
> (t/run adding)
=> #Task{:executed? true, :status :success, :value 2}
```
This type of execution will naturally block the current thread until the tasks finishes.

### Asynchronous execution
```Clojure
> (t/run-async adding)
=> #Task{:executed? false, :status :pending, :value nil}
```
Running a task asynchronously will not block the current thread and will return immediately.
The task itself captures a promise which will eventually be filled with the result of that execution.

**Note**: `wait` can be used to block explicitly.

### Task values
In some cases, you may want to retrieve the actual inner value of a task.

This can be achieved either with `get!`, `deref` or `@` and these can return one of two things:

* If a task succeeded, it will return the concrete value of that execution:
```clojure
> (t/get! (t/run adding))
=> 2

> @(t/run adding)
=> 2
```

* If a task failed, it will contain a map version of the `Exception` that occurred:
```clojure
> (-> (t/task (throw (Exception. "Something went wrong!")))
      (t/run)
      (t/get!))

=> { :cause "Something went wrong!", 
     :via [...],
     :trace [...] }
```

There's a separate `get-or-else` function, which will return either the value in
case of a success, or a provided `else` alternative in case of failure:
```clojure
> (-> (task 1)
      (t/run)
      (t/get-or-else -1))
=> 1

> (-> (t/task (throw (Exception. "Nope")))
      (t/run)
      (t/get-or-else -1))
=> -1
```

**Note:** All of these will **block** an asynchronously executing task.

### Task status
There are a number of functions that check different types of a task's status:

* `done?` - checks if a **running task** has **finished running**
* `executed?` - checks if a task **has been run** and has **finished running**
* `fulfilled?` - checks if a **running task** has **finished running** and was **successful**
* `broken?` - checks if a **running task** has **finished running** and **failed**

In addition, you can create finished successful or failed tasks with:

* `success` -  given any value, returns a finished successful task containing that value
* `failure` - given a string message, returns a finished failed task with that message wrapped in a Throwable as an error
* `failure-t` - given a proper `Exception` or `Throwable` object, returns a finished failed task with that error

### Composing tasks
Tasks can be composed by using the `then` primitive. This takes a
task and some sort of callback function, and returns a new task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then inc)
                         (t/then dec)))
=> #'user/crucial-maths

> @(t/run crucial-maths)
=> 2
```
Additionally, the callback function can either return a simple value or another task:
```Clojure
> (def crucial-maths (-> (t/task (+ 1 1))
                         (t/then #(t/task (inc %)))
                         (t/then dec)))
=> #'user/crucial-maths

> @(t/run crucial-maths)
=> 2
```
By the magic of referential transparency, this leads to the same outcome.

### Composition after execution
Tasks maintain composability after execution. Every time they get run, they return new tasks
containing the future results. Because tasks are lazy, once you've run a task and
afterwards composed new things into it, you'll have to run it again in order to force the new compositions.

Example:
```Clojure
> (def crucial-math (-> (t/task (+ 1 1))
                        (t/then #(t/task (inc %)))
                        (t/run) ;; <- (inc (+ 1 1))
                        (t/then dec))) ;; <- unexecuted
=> #'user/crucial-math
```
In this case `run` (and also `run-async`) will only execute those tasks that came before its invocation.
If additional compositions are made after or while it's executing, these shall remain un-executed until another
call to either `run` or `run-async` is made:
```Clojure
> @(t/run crucial-math)
=> 2
```
The task will then pick up where it's left off and execute the remaining changes.

### Fire-and-forget effects
If you're not interested in the return value of some previous task,
you can chain fire-and-forget-like task effects by using the `then-do` macro.
`then-do` sequentially composes effects into one task:
```clojure
> @(-> (t/task (println "Launching missiles!"))
       (t/then-do (println "Missiles launched!"))
       (t/then-do (println "Death is imminent!"))
       (t/run))

Launching missiles!
Missiles launched!
Death is imminent!
=> nil
```
This is equivalent to:
```clojure
> @(-> (t/task (println "Launching missles!")
       (t/task (fn [_] (println "Missiles launched!")))
       (t/task (fn [_] (println "Death is imminent!")))
       (t/run))
```

### Task recovery
A potentially failed task may be recovered with either `recover` or `recover-as`.

Both of these may well return either simple values, or tasks alltogether.

`recover` allows you to recover a task, based on the error that occured, whilst `recover-as` simply
ignores the error and lets you reset the task to any given value after failure.

```clojure
> @(-> (t/task (throw (Exception. "Failed)))
       (t/recover #(.getMessage %))
       (t/run))

=> "Failed"
```

```clojure
> @(-> (t/task (throw (Exception. "Failed")))
       (t/recover-as -1)
       (t/run))

=> -1
```

### Task comprehension
Whilst threading tasks from one to the other looks
pretty, it isn't particuarly suited for working with
mutliple interdependent tasks.

For this there is `do-tasks`:
```Clojure
> (def crucial-maths 
      (t/do-tasks [a (t/task (+ 1 1))
                   b1 (t/task (inc a))
                   b2 (dec a)]
                  (+ a (- b1 b2))))
=> #'user/crucial-maths

> @(t/run crucial-maths)
=> 4
```
With this, you can use binding-forms to treat task
values as if they were realized, and use them in that local context.
`do-tasks` accepts both simple values and other tasks. It automatically "promotes"
simple values to tasks in order to work with them.

As of `1.2.1`, `do-tasks` also supports syntax for `recover` and `recover-as`.

These can be placed wherever within the `do-tasks` binding block:

```clojure
> (def crucial-maths
      (t/do-tasks [a (t/task (+ 1 1))
                   b1 (t/task (inc a))
                   b2 (dec a)
                   :recover #(.getMessage %)]
                  (+ a (- b1 b2))))
=> #'user/crucial-maths
```

<b>Note:</b> `do-tasks` essentially desugars to nested `then`-calls,
which means that the binding-forms are <i>serialised</i>.

### Parallelism
Halfing supports parallel execution with the functions:

 * `mapply` - given any number of tasks and a function of arity equal to that number,
   will call that function with all the values of those tasks if they are successful:

```clojure
> (def task1 (t/task 1))
=> #'halfling.task/task1

> (def task2 (t/task 2))
=> #'halfling.task/task2

> (def task3 (t/task 3))
=> #'halfling.task/task3

> @(t/run (t/mapply (fn [a b c] (+ a b c)) task1 task2 task3)) ; task1, task2, task3 executed in parallel
=> 6
```

 * `zip` - takes any number of tasks and returns a task, which, in case of success, aggregates their values
   in a vector:

```clojure
> @(t/run (t/zip task1 task2 task3)) ; task1, task2, task3 executed in parallel
=> [1 2 3]
```

 * `sequenced` - takes a collection of tasks and returns a task, which, in case of success, aggregates the values of
   those tasks in the same type of collection:

```clojure
> @(t/run (t/sequenced #{task1 task2 task3})) ; task1, task2, task3 executed in sequence
=> #{1 2 3}
```

Failed tasks will contain the error of the **first** execution that failed.
See `halfing.task` for more information.

#### Library functions
As of version `1.0.0` halfling has a separate namespace called `lib`, which contains
different types of library functions that use the `task` API in their implementation.

Current functions:
 * `p-map` - just like `map` but returns a task, which applies the function in parallel.
 Similarly to Clojure's `pmap`, should only be used when the computation performed outweighs
 the distribution overhead.

 An example usage:
```clojure
> (require '[halfling.lib :refer [p-map]])
=> nil

> (defn letters [start-char]
   (iterate (comp char inc int) start-char))
=> #'user/letters

> (def alph (vec (concat
                   (take 26 (letters \A))
                   (take 26 (letters \a)))))
=> #'user/alph

> (defn rand-str [n]
   (->> (range 0 n)
        (map (fn [_] (rand-nth alph)))
        (apply str)))
=> #'user/rand-str

> (defn strings [n length]
   (->> (range 0 n)
        (map (fn [_] (rand-str n)))))
=> #'user/strings

> (def work (->> (strings 4000 1000)
                 (p-map clojure.string/lower-case)))
=> #'user/work

> (time (do (t/run work) ()))
"Elapsed time: 1.258364 msecs"
```
## License

Copyright © 2017-2021 Robert Marius Avram

Distributed under the MIT License.
