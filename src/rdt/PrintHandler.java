package rdt;

public class PrintHandler {

    static int printLevel = 0;

    final static int printLevelMax = 5;
    final static int printLevelMin = 0;

    public static void printOnLevel(int level, String x) {
        if (level <= printLevel) {
            System.out.println(x);
        }
    }

}
