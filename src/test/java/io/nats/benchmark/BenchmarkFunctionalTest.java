package io.nats.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.ConnectionImpl;
import io.nats.client.Statistics;
import io.nats.client.TestCasePrinterRule;
import io.nats.client.UnitTest;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Category(UnitTest.class)
public class BenchmarkFunctionalTest {
    static final Logger logger = LoggerFactory.getLogger(BenchmarkFunctionalTest.class);

    @Rule
    public TestCasePrinterRule pr = new TestCasePrinterRule(System.out);

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {}

    @AfterClass
    public static void tearDownAfterClass() throws Exception {}

    @Before
    public void setUp() throws Exception {}

    @After
    public void tearDown() throws Exception {}

    static final int MSG_SIZE = 8;
    static final int MILLION = 1000 * 1000;
    static final int BILLION = MILLION * 1000;
    static final double EPSILON = 1.0 / 1.0E18;

    long baseTime = System.nanoTime();

    /**
     * Returns a million message sample.
     * 
     * @return a Sample for one million messages
     */
    public Sample millionMessagesSecondSample(int seconds) {
        int messages = MILLION * seconds;
        long start = baseTime;
        long end = start + TimeUnit.SECONDS.toNanos(seconds);

        final ConnectionImpl nc = mock(ConnectionImpl.class);
        when(nc.getStats()).thenReturn(new Statistics());
        Sample stat = new Sample(messages, MSG_SIZE, start, end, nc);
        stat.msgCnt = (long) messages;
        stat.msgBytes = (long) messages * MSG_SIZE;
        stat.ioBytes = stat.msgBytes;
        return stat;
    }

    @Test
    public void testDuration() {
        Sample stat = millionMessagesSecondSample(1);
        long duration = stat.end - stat.start;
        assertEquals(1L, TimeUnit.NANOSECONDS.toSeconds(stat.duration()));
        assertEquals(stat.duration(), duration);
    }

    @Test
    public void testHumanBytes() {
        double bytes = 999;
        assertEquals("999.00 B", Utils.humanBytes(bytes, true));

        bytes = 2099;
        assertEquals("2.10 kiB", Utils.humanBytes(bytes, true));
    }

    @Test
    public void testSeconds() {
        Sample stat = millionMessagesSecondSample(1);
        double seconds = TimeUnit.NANOSECONDS.toSeconds(stat.end - stat.start) * 1.0;
        assertEquals(1.0, seconds, EPSILON);
        assertEquals(seconds, stat.seconds(), EPSILON);
    }

    @Test
    public void testRate() {
        Sample stat = millionMessagesSecondSample(60);
        assertEquals(MILLION, stat.rate());
    }

    @Test
    public void testThroughput() {
        Sample stat = millionMessagesSecondSample(60);
        assertEquals(MILLION * MSG_SIZE, stat.throughput(), EPSILON);
    }

    @Test
    public void testToString() {
        Sample stat = millionMessagesSecondSample(60);
        assertNotNull(stat.toString());
        assertFalse(stat.toString().isEmpty());
    }

    @Test
    public void testGroupDuration() {
        SampleGroup group = new SampleGroup();
        group.addSample(millionMessagesSecondSample(1));
        group.addSample(millionMessagesSecondSample(2));
        double duration = group.end - group.start;
        assertEquals(group.duration(), duration, EPSILON);
        assertEquals(2.0, duration / BILLION, EPSILON);
    }

    @Test
    public void testGroupSeconds() {
        SampleGroup group = new SampleGroup();
        group.addSample(millionMessagesSecondSample(1));
        group.addSample(millionMessagesSecondSample(2));
        group.addSample(millionMessagesSecondSample(3));
        double seconds = (group.end - group.start) / BILLION * 1.0;
        assertEquals(group.seconds(), seconds, EPSILON);
        assertEquals(3.0, seconds, EPSILON);
    }

    @Test
    public void testGroupRate() {
        SampleGroup group = new SampleGroup();
        group.addSample(millionMessagesSecondSample(1));
        group.addSample(millionMessagesSecondSample(2));
        group.addSample(millionMessagesSecondSample(3));
        assertEquals(2 * MILLION, group.rate());
    }

    @Test
    public void testGroupThroughput() {
        SampleGroup group = new SampleGroup();
        group.addSample(millionMessagesSecondSample(1));
        group.addSample(millionMessagesSecondSample(2));
        group.addSample(millionMessagesSecondSample(3));
        assertEquals(2 * MILLION * MSG_SIZE, group.throughput(), EPSILON);
    }

    @Test
    public void testMinMaxRate() {
        SampleGroup group = new SampleGroup();
        group.addSample(millionMessagesSecondSample(1));
        group.addSample(millionMessagesSecondSample(2));
        group.addSample(millionMessagesSecondSample(3));
        assertEquals(group.minRate(), group.maxRate());
    }

    @Test
    public void testAvgRate() {
        SampleGroup group = new SampleGroup();
        group.addSample(millionMessagesSecondSample(1));
        group.addSample(millionMessagesSecondSample(2));
        group.addSample(millionMessagesSecondSample(3));
        assertEquals(group.minRate(), group.avgRate());
    }

    @Test
    public void testStdDev() {
        SampleGroup group = new SampleGroup();
        group.addSample(millionMessagesSecondSample(1));
        group.addSample(millionMessagesSecondSample(2));
        group.addSample(millionMessagesSecondSample(3));
        assertEquals(0.0, group.stdDev(), EPSILON);
    }

    @Test
    public void testBenchSetup() {
        Benchmark bench = new Benchmark("test", 1, 1);
        bench.addPubSample(millionMessagesSecondSample(1));
        bench.addSubSample(millionMessagesSecondSample(1));
        bench.close();
        assertNotNull(bench.runId);
        assertFalse(bench.runId.isEmpty());
        assertEquals(1, bench.pubs.samples.size());
        assertEquals(1, bench.subs.samples.size());
        assertEquals(2 * MILLION, bench.msgCnt);
        assertEquals(2 * MILLION * MSG_SIZE, bench.ioBytes);
        assertEquals(1, TimeUnit.NANOSECONDS.toSeconds(bench.duration()));
    }

    /**
     * Creates a Benchmark object with test data.
     * 
     * @param subs number of subscribers
     * @param pubs number of publishers
     * @return the created Benchmark
     */
    public Benchmark makeBench(int subs, int pubs) {
        Benchmark bench = new Benchmark("test", subs, pubs);
        for (int i = 0; i < subs; i++) {
            bench.addSubSample(millionMessagesSecondSample(1));
        }
        for (int i = 0; i < pubs; i++) {
            bench.addPubSample(millionMessagesSecondSample(1));
        }
        bench.close();
        return bench;
    }

    @Test
    public void testCsv() {
        Benchmark bench = makeBench(1, 1);
        List<String> lines = bench.csv();
        assertEquals("Expected 3 lines of output from csv()", 3, lines.size());
        String[] fields = lines.get(1).split(",");
        assertEquals("Expected 7 fields", 7, fields.length);
    }

    @Test
    public void testBenchStrings() {
        Benchmark bench = makeBench(1, 1);
        String report = bench.report();
        String[] lines = report.split("\n");
        assertEquals(3, lines.length);
        assertEquals("Expected 3 lines of output: header, pub, sub", 3, lines.length);

        bench = makeBench(2, 2);
        report = bench.report();
        lines = report.split("\n");
        String str = String
                .format("%s\nExpected 9 lines of output: header, pub header, pub x 2, stats, sub "
                        + "headers, sub x 2, stats", report);
        assertEquals(str, 9, lines.length);
    }

    @Test
    public void testMsgsPerClient() {
        List<Integer> zero = Utils.msgsPerClient(0, 0);
        assertTrue("Expected 0 length for 0 clients", zero.isEmpty());

        List<Integer> oneTwo = Utils.msgsPerClient(1, 2);
        System.err.println(oneTwo);
        assertTrue("Expected uneven distribution",
                oneTwo.size() == 2 && (oneTwo.get(0) + oneTwo.get(1) == 1));

        List<Integer> twoTwo = Utils.msgsPerClient(2, 2);
        assertTrue("Expected even distribution",
                twoTwo.size() == 2 && twoTwo.get(0) == 1 && twoTwo.get(1) == 1);

        List<Integer> threeTwo = Utils.msgsPerClient(3, 2);
        assertTrue("Expected uneven distribution",
                threeTwo.size() == 2 && threeTwo.get(0) == 2 && threeTwo.get(1) == 1);

    }
}
