package _40c.nqUtilities.utilities;

/**
 * Nifty1MinDataShredder
 *
 * <p>Standalone utility for processing ("shredding") Nifty 1-minute OHLCV data.
 * A typical job is to take one large CSV of 1-minute bars and split it into
 * smaller files — for example one file per trading day or per symbol.
 *
 * <p>Usage:
 * <pre>
 *   java _40c.nqUtilities.utilities.Nifty1MinDataShredder &lt;inputFile&gt; [outputDir]
 * </pre>
 */
public class Nifty1MinDataShredder {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String inputFile = args[0];
        String outputDir = args.length > 1 ? args[1] : ".";

        System.out.println("Nifty1MinDataShredder");
        System.out.println("  input file : " + inputFile);
        System.out.println("  output dir : " + outputDir);

        // TODO: implement shredding logic here.
    }

    private static void printUsage() {
        System.out.println("Usage: java _40c.nqUtilities.utilities.Nifty1MinDataShredder <inputFile> [outputDir]");
    }
}
