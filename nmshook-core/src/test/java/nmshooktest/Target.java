package nmshooktest;

import java.util.List;

/** Stand-in for an NMS class. Lives outside dev.seraphicness.* like real server classes. */
public class Target extends TargetBase {

    public int counter;
    private int secret = 7;

    public int add(int a, int b) {
        return a + b;
    }

    public int add(int a, int b, int c) {
        return a + b + c;
    }

    public String greet(String name) {
        return "hello " + name;
    }

    public void touch() {
        counter++;
    }

    public static long square(long x) {
        return x * x;
    }

    public int compute(int x) {
        int y = x * 2;
        y = helper(y);
        return y + 1;
    }

    int helper(int v) {
        return v + 10;
    }

    public int sizeOf(List<?> list) {
        return list.size();
    }

    public int maxOf(int a, int b) {
        return Math.max(a, b);
    }

    public boolean flag() {
        return true;
    }

    public int readSecret() {
        return secret;
    }

    public double half(double d) {
        double h = d / 2;
        return h;
    }

    public String chain(String s) {
        String t = s.trim();
        return t.toUpperCase();
    }
}
