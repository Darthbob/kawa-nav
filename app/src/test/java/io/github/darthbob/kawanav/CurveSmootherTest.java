package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The curve on the dash does not flip back and forth: the sequences are the logs' own (22 and 25 Oct). */
public class CurveSmootherTest {
    private static final int STRAIGHT = 0, LEFT = 1, RIGHT = 2, SHARP_LEFT = 3, SHARP_RIGHT = 4, RBT_CCW_2 = 31;

    /** Runs {turn, metres} pairs one fix a second at {@code speedMs}; returns "turn distance" as shown. */
    private static List<String> run(double speedMs, int... turnAndMetres) {
        CurveSmoother s = new CurveSmoother();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < turnAndMetres.length; i += 2) {
            Hub.Output o = s.next(new Hub.Output(turnAndMetres[i], turnAndMetres[i + 1], "Curve"), i / 2 * 1000L, speedMs);
            out.add(o.turn + " " + o.distanceM);
        }
        return out;
    }

    private static List<Integer> turns(List<String> shown) {
        List<Integer> out = new ArrayList<>();
        for (String s : shown) out.add(Integer.parseInt(s.split(" ")[0]));
        return out;
    }

    @Test
    public void aBendAtTheEdgeOfTwoKindsKeepsItsKind() {
        // 22 Oct 15:40: Sharp right 380, Right 370, Sharp right 360, 340, 330, Right 310, Sharp right 290
        List<String> shown = run(15, SHARP_RIGHT, 380, RIGHT, 370, SHARP_RIGHT, 360, SHARP_RIGHT, 340,
                SHARP_RIGHT, 330, RIGHT, 310, SHARP_RIGHT, 290);
        assertEquals(List.of(4, 4, 4, 4, 4, 4, 4), turns(shown));
        assertEquals("4 370", shown.get(1));             // held, and nearer by the 15 m ridden
    }

    @Test
    public void aBendFoundOnOneFixAndNotTheNextStays() {
        // 22 Oct 15:34: Right 480, Straight 1520, Right 450, Straight 1490, Right 420
        List<String> shown = run(25, RIGHT, 480, STRAIGHT, 1520, RIGHT, 450, STRAIGHT, 1490, RIGHT, 420);
        assertEquals(List.of(2, 2, 2, 2, 2), turns(shown));
    }

    @Test
    public void afterARoundaboutTheNextBendSettlesOnce() {
        // 25 Oct 10:05: exit 2 in 9 m, then Sharp right 840, Sharp left 680, Sharp right 890, Sharp left 650, 640
        List<String> shown = run(20, RBT_CCW_2, 9, SHARP_RIGHT, 840, SHARP_LEFT, 680, SHARP_RIGHT, 890,
                SHARP_LEFT, 650, SHARP_LEFT, 640);
        assertEquals(List.of(31, 4, 3, 3, 3, 3), turns(shown));
    }

    @Test
    public void aChangeFoundTwiceIsShown() {
        List<String> shown = run(10, RIGHT, 400, SHARP_RIGHT, 390, SHARP_RIGHT, 380);
        assertEquals(List.of(2, 2, 4), turns(shown));
    }

    @Test
    public void aBendIsNeverHeldBack() {
        // a bend appearing on a straight, or clearly closer than the one shown, is shown at once
        assertEquals(List.of(0, 1), turns(run(20, STRAIGHT, 1500, LEFT, 900)));
        assertEquals(List.of(2, 1), turns(run(20, RIGHT, 800, LEFT, 300)));
    }

    @Test
    public void onceReachedTheNextOneComesAtOnce() {
        assertEquals(List.of(2, 1), turns(run(10, RIGHT, 10, LEFT, 600)));
    }

    @Test
    public void nothingToShowIsShownAtOnce() {
        CurveSmoother s = new CurveSmoother();
        s.next(new Hub.Output(RIGHT, 300, "Curve"), 0, 20);
        assertNull(s.next(null, 1000, 20));
        // and what comes after starts afresh
        assertEquals(STRAIGHT, s.next(new Hub.Output(STRAIGHT, 1500, "Curve"), 2000, 20).turn);
    }
}
