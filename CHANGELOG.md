# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

### 0.1.6
### Changed
`fold` in `Result` is now a macro to avoid unnecessary function calls

### Added
Function `from-result` on `Task`, which promotes a `Result` to a `Task` 
Invariant here is: for some `Result` r, (run (from-result r)) == r 

### 0.1.5
### Changed
`get!` for `Result` now returns the interval value for both failure and success

### Added
`get-or-else` function for `Task`. This runs the task synchronously and then returns the inner
result value. Equivalent to `(halfling.result/get! (halfling.task/run task))`

### 0.1.4
### Changed
* Tasks and Results are type-checked more accurately
* `Result` is now a `deftype`

### Added
* `recover` function added for both `Task` and `Result`. This applies a function on a result in case of failure

### 0.1.3
### Changed
- Renamed `ap` to `mapply`, because `ap` wasn't really the true `ap` function, but rather
the applicative `apply-n` function.

### 0.1.2
### Changed
- Change `fold` for `Result` to apply its functions directly on the values of those results and not the results themselves

## 0.1.1
### Changed
- `Task` behaviour to support parallel execution
- `ap`, `zip` and `sequenced` support parallel execution 

### Added
- `p-map`, an alternative to clojures `pmap`, that uses the library to map a function in parallel

## 0.1.0
### Added
- Library release
