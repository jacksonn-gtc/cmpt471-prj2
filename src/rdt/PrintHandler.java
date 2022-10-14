package rdt;

public class PrintHandler {

    private static int printLevel = 1;

    private final static int printLevelMax = 5;
    private final static int printLevelMin = 0;

    public static void printOnLevel(int level, String x) {
        if (level <= printLevel) {
            System.out.println(x);
        }
    }

}
