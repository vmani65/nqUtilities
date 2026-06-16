# nqUtilities

Standalone Java utilities for NQ / Nifty market-data processing.

## Main class

- `_40c.nqUtilities.utilities.Nifty1MinDataShredder` — processes ("shreds") Nifty
  1-minute OHLCV data, e.g. splitting one large CSV into smaller per-day /
  per-symbol files.

## Requirements

- JDK 21 (no external dependencies)

## Build & run

From the project root:

```sh
# compile to the out/ directory
javac -d out src/main/java/_40c/nqUtilities/utilities/Nifty1MinDataShredder.java

# run
java -cp out _40c.nqUtilities.utilities.Nifty1MinDataShredder <inputFile> [outputDir]
```

## Layout

```
nqUtilities/
  src/main/java/_40c/nqUtilities/utilities/Nifty1MinDataShredder.java
  out/                # compiled classes (generated)
  README.md
  .gitignore
```
