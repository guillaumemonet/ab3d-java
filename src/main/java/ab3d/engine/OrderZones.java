package ab3d.engine;

import ab3d.data.FloorLine;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.m68k.M68k;

/**
 * {@code OrderZones}, transcribed from source/orderzones.
 *
 * The routine sorts the zones a room can see into back-to-front drawing order.
 * It keeps them in a doubly linked list of eight-byte slots -- previous, zone
 * number, next -- and repeatedly walks a zone's bounding lines, moving any
 * neighbour that turns out to be nearer to the far side of it.
 *
 * Whether a neighbour has been seen, and which side of a line it fell on, is
 * held in a bitmask carried per zone: three bits for each bounding line, packed
 * into the longword each graph entry stores after its zone number and clip
 * index. That longword is read in at the start and written back at the end, so
 * the flags persist for the rest of the frame.
 *
 * The AB3D1 and newsource copies of this routine differ only in where the labels
 * sit on the line.
 */
public final class OrderZones {

    /** {@code OrderTab: ds.l 400} -- 1600 bytes, eight per slot. */
    private static final int SLOTS = 200;
    /** {@code FinalOrder: ds.l 400}, written as words. */
    private static final int MAX_ORDER = 800;
    /** {@code move.w #100,d7}: how many passes the insertion is allowed. */
    private static final int PASSES = 100;

    private final Level level;

    // OrderTab, one array per word field of a slot
    private final int[] prev = new int[SLOTS];
    private final int[] zoneOf = new int[SLOTS];
    private final int[] next = new int[SLOTS];

    /**
     * {@code ToDrawTab}: set for every zone that will be drawn at some stage.
     * The original clears 400 bytes of it and indexes it by zone number, so it
     * only ever holds zones 0 to 399. Nothing in this routine reads it back.
     */
    private final boolean[] toDraw;
    /** {@code WorkSpace}: the per-zone flag bitmask the routine reads and writes. */
    private final int[] workSpace;

    /** {@code FinalOrder}: zone numbers, furthest first. */
    public final int[] finalOrder = new int[MAX_ORDER];
    /** How many entries {@link #finalOrder} holds. */
    public int orderCount;
    /**
     * Relinks the last run performed, and how many of them happened in the last
     * ten passes. The list is meant to settle long before the hundred passes are
     * used up, so a non-zero tail means the sort never converged.
     */
    public int relinks, relinksInTail;
    private int passesLeft;

    public OrderZones(Level level) {
        this.level = level;
        this.toDraw = new boolean[level.zones.length];
        this.workSpace = new int[level.zones.length];
    }

    /**
     * Orders the rooms a zone lists as visible.
     *
     * @param rooms the zone's graphics list: which zones it can see
     * @param xoff  viewer x, as the routine reads {@code xoff}
     * @param zoff  viewer z
     */
    public void run(java.util.List<Zone.GraphEntry> rooms, int xoff, int zoff) {
        // moveq #99,d0 / .clrtab: clear ToDrawTab
        java.util.Arrays.fill(toDraw, false);

        // settodraw: st (a3,d0.w) / move.l 4(a1),(a4,d0.w*4)
        // A negative entry ends the walk (blt.s nomoreset); it is not skipped.
        for (Zone.GraphEntry e : rooms) {
            int d0 = e.graphNumber();
            if (d0 < 0) {
                break;
            }
            if (d0 >= toDraw.length) {
                continue;
            }
            toDraw[d0] = true;
            workSpace[d0] = e.cord();
        }

        // putinn: build the initial list, one slot per room, in listed order
        int slot = 0;                       // a2 starts at OrderTab
        int d0 = 0, d1 = 2;                 // moveq #0,d0 / moveq #2,d1
        for (Zone.GraphEntry e : rooms) {
            int d2 = e.graphNumber();
            if (d2 < 0) {
                break;
            }
            slot++;                         // addq #8,a2
            if (slot >= SLOTS) {
                break;
            }
            zoneOf[slot] = d2;              // move.w d2,2(a2)
            prev[slot] = d0;                // move.w d0,(a2)
            next[slot] = d1;                // move.w d1,4(a2)
            d0++;
            d1++;
        }
        // putallin
        next[slot] = -1;                    // move.w #-1,4(a2)
        next[0] = 1;                        // move.w #1,OrderTab+4
        prev[0] = -1;                       // move.w #-1,OrderTab
        zoneOf[0] = -1;                     // move.w #-1,OrderTab+2

        relinks = 0;
        relinksInTail = 0;
        int a5 = slot;                      // move.l a2,a5
        int d7 = PASSES;                    // move.w #100,d7

        // RunThroughList
        while (true) {
            int zone = zoneOf[a5];          // move.w 2(a5),d0
            if (zone < 0 || zone >= workSpace.length) {
                break;
            }
            passesLeft = d7;
            int d6 = workSpace[zone];       // move.l (a6),d6
            int a4 = a5;                    // move.l a5,a4

            int nextSlot = prev[a5];        // move.w (a5),d0
            if (nextSlot < 0) {
                break;                      // blt doneallthispass
            }
            a5 = nextSlot;                  // lea (a5,d0.w*8),a5
            d6 = insertList(zone, a4, d6, xoff, zoff);
            workSpace[zone] = d6;

            if (--d7 < 0) {
                break;                      // dbra d7,RunThroughList
            }
        }

        // dontorder / showorder: walk the list from the head, emitting zones
        orderCount = 0;
        int cursor = next[0];               // move.w 4(a5),d0
        while (cursor >= 0 && cursor < SLOTS && orderCount < MAX_ORDER) {
            finalOrder[orderCount++] = zoneOf[cursor];   // move.w 2(a5),(a0)+
            cursor = next[cursor];                        // move.w 4(a5),d0
        }
    }

    /**
     * {@code InsertList}: walks one zone's bounding lines and moves any
     * neighbour that is actually nearer to the far side of it.
     *
     * d7 steps three bits per line: the first marks the line as leading into the
     * draw list, the next two record which side of it the viewer fell on.
     */
    private int insertList(int zone, int a4, int d6, int xoff, int zoff) {
        int d7 = 0;                         // moveq #0,d7
        Zone z = level.zone(zone);

        for (int lineIndex : z.exitLines) { // InsertLoop: move.w (a0)+,d0 / blt allinlist
            if (lineIndex < 0 || lineIndex >= level.floorLines.length) {
                break;
            }
            FloorLine fl = level.floorLine(lineIndex);   // asl.w #4,d0
            int d1 = fl.toZone;             // move.w 8(a1,d0.w),d1

            if (d1 < 0 || !bit(d6, d7)) {   // blt buggergerger / btst d7,d6
                d7 += 3;                    // buggergerger: addq #3,d7
                continue;
            }
            // indrawlist
            d7 += 1;                        // addq #1,d7
            boolean known = bit(d6, d7);    // btst d7,d6

            boolean mustDo;
            if (!known) {
                d6 = set(d6, d7);           // bset d7,d6
                // the side test, in the routine's own order
                int d2 = M68k.w(xoff - fl.x1);          // sub.w (a1,d0.w),d2
                int d3 = M68k.w(zoff - fl.z1);          // sub.w 2(a1,d0.w),d3
                d2 = M68k.muls(d2, fl.dz);              // muls 6(a1,d0.w),d2
                d3 = M68k.muls(d3, fl.dx);              // muls 4(a1,d0.w),d3
                d7 += 1;                                // addq #1,d7
                d2 = d2 - d3;                           // sub.l d3,d2
                if (d2 <= 0) {                          // ble PutDone
                    d7 += 1;
                    continue;
                }
                d6 = set(d6, d7);                       // bset d7,d6
                mustDo = true;
            } else {
                d7 += 1;                                // wealreadyknow: addq #1,d7
                mustDo = bit(d6, d7);                   // beq PutDone
            }

            if (mustDo) {
                moveInFront(a4, d1);
            }
            d7 += 1;                                    // PutDone: addq #1,d7
        }
        return d6;                                      // allinlist: move.l d6,(a6)
    }

    /**
     * The list surgery of {@code mustdo}: find the slot holding zone {@code d1}
     * on the near side of {@code a4} and relink it to the far side.
     */
    private void moveInFront(int a4, int d1) {
        int d0 = prev[a4];                  // move.w (a4),d0
        while (d0 >= 0 && d0 < SLOTS) {     // checkcloser
            if (zoneOf[d0] == d1) {         // cmp.w 2(a3,d0.w*8),d1 / beq iscloser
                int d2 = prev[a4];          // move.w (a4),d2
                int d5 = next[d2];          // move.w 4(a3,d2.w*8),d5
                int d3 = next[a4];          // move.w 4(a4),d3
                if (d3 >= 0) {              // blt.s fromend
                    prev[d3] = d2;          // move.w d2,(a3,d3.w*8)
                }
                next[d2] = d3;              // fromend: move.w d3,4(a3,d2.w*8)

                d2 = prev[d0];              // move.w (a3,d0.w*8),d2
                int d4 = next[d2];          // move.w 4(a3,d2.w*8),d4
                if (d5 >= 0 && d5 < SLOTS) {
                    prev[d5] = d2;          // move.w d2,(a3,d5.w*8)
                    next[d5] = d4;          // move.w d4,4(a3,d5.w*8)
                }
                if (d4 >= 0 && d4 < SLOTS) {
                    prev[d4] = d5;          // move.w d5,(a3,d4.w*8)
                }
                next[d2] = d5;              // move.w d5,4(a3,d2.w*8)
                relinks++;
                if (passesLeft < 10) {
                    relinksInTail++;
                }
                return;
            }
            d0 = prev[d0];                  // move.w (a3,d0.w*8),d0 / bge checkcloser
        }
        // notcloser: nothing to do
    }

    private static boolean bit(int value, int n) {
        return (value & (1 << (n & 31))) != 0;
    }

    private static int set(int value, int n) {
        return value | (1 << (n & 31));
    }
}
