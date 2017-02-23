import io.github.mkuchin.RequestCounter;
import junit.framework.TestCase;

import java.util.concurrent.TimeUnit;

public class TestCounter extends TestCase {
    public void testSingleThread() throws InterruptedException {
        RequestCounter counter = new RequestCounter(1, TimeUnit.SECONDS);

        int n = 10;
        for(int i = 0; i < n; i++) {
            counter.hit();
        }
        //time = 0
        assertEquals(n, counter.getSize());
        Thread.sleep(500);
        for(int i = 0; i < n; i++) {
            counter.hit();
        }
        //time = 0.5
        assertEquals(n*2, counter.getSize());
        Thread.sleep(500);
        counter.hit();
        //time = 1
        assertEquals(n+1, counter.getSize());
        Thread.sleep(500);
        //time = 1.5
        assertEquals(1, counter.getSize());
        Thread.sleep(500);
        //time = 2
        assertEquals(0, counter.getSize());
        //---------------------------------------------
        //time      0       0.5     1       1.5     2
        //hits      10      10      1       0       0
        //hits/s    10      20      11      1       0
    }

}
