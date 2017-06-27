# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

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