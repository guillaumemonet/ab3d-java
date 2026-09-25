package ab3d.engine;

import ab3d.data.FloorLine;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.m68k.M68k;

/**
 * {@code MoveObject}, transcribed from source/ObjectMove.
 *
 * The mover is not a circle pushed out of walls. For each bounding line of the
 * room it works out the cross product of the move against the line, divides by
 * the line's length plus the mover's own reach, and reads three things off the
 * result: a positive value under 32 means close enough to touch -- which is how a
 * door learns someone is there -- and a value at or below zero means the point
 * has gone past the line, at which point the height at the crossing decides
 * whether the line is a wall or an opening. A wall projects the point back onto
 * itself, so the mover slides rather than stopping.
 *
 * Two things in the shipped build are worth knowing. The step-up and step-down
 * tests inside the first two blocks are jumped over by unconditional branches --
 * the code is there but never runs -- so the heights that matter are the four
 * the routine records before them. And the whole thing repeats until a pass
 * changes nothing, because sliding along one wall can push the point past
 * another.
 */
public final class MoveObject {

    /** {@code move.w #200,QUITOUT}: how many passes before giving up. */
    private static final int QUIT_OUT = 200;
    /** {@code cmp.w #32,d0}: closer than this and the line is flagged. */
    private static final int TOUCH = 32;
    /** The value the four height slots start at, {@code #-65536*256}. */
    private static final int NO_HEIGHT = -65536 * 256;
    /** {@code cmp.w #4,d7}: slack at each end of a line segment. */
    private static final int SLACK = 4;
    /**
     * Whether a move that lands in no room is thrown away.
     *
     * On, and it has to be. The original has no such step -- it relies on its two
     * wall passes to keep the point in a room -- but the slide here leaves a
     * residue, and walking straight into a wall accumulates it: a run of ninety
     * steps into one wall of level_a ends eleven units past a line and stays
     * there. Outside the room, every surface of that room is behind the viewer,
     * so the floor and ceiling are all rejected and the player sees straight
     * through the world.
     *
     * Refusing the move is not what the original does, and it is written here
     * rather than hidden because it stands in for a slide that has not been made
     * exact. The visible cost is that a wall taken at a very shallow angle stops
     * the player instead of sliding them a fraction further along it.
     */
    public static boolean REFUSE_STRAY = true;

    private final Level lv;

    /** Set where the move ended up. */
    public int x, z, zone;
    /** {@code hitwall}: true when at least one wall turned the move. */
    public boolean hitWall;
    /**
     * {@code StoodInTop}: which storey the mover is on.
     *
     * Set at a floor-line crossing, from the height at the crossing point
     * against the roof of the room being entered. The caller hands in what it
     * had and gets back what it has.
     */
    public boolean stoodInTop;
    /** Passes the resolution took, for checking it settles. */
    public int passes;
    /** Where lines beyond the point went: height let it through, segment missed, slid. */
    public static int beyond, byHeight, bySegment, slid, refused;
    /** Where the second pass gave up. */
    public static int p2seen, p2open, p2notPast, p2noStraddle, p2oldOutside, p2slid;

    public MoveObject(Level level) {
        this.lv = level;
    }

    /**
     * Moves a thing and resolves it against the room's walls.
     *
     * @param extLen      {@code extlen}, the mover's reach; 40 for the player
     * @param thingHeight {@code thingheight}
     * @param stepUp      {@code StepUpVal}
     * @param flags       {@code wallflags}, the bit to leave on lines touched
     */
    public void move(int fromZone, int oldX, int oldZ, int newX, int newZ,
                     int oldY, int newY, int extLen, int thingHeight, int stepUp,
                     int stepDown, int flags) {
        move(fromZone, oldX, oldZ, newX, newZ, oldY, newY, extLen, thingHeight,
             stepUp, stepDown, flags, NO_STANDOFF);
    }

    /** {@code move.b PLR1_StoodInTop,StoodInTop}: hand the flag in before a move. */
    public void enterStorey(boolean inTop) {
        stoodInTop = inTop;
    }

    /** {@code move.b #$ff,awayfromwall}: a bullet is a point, and touches walls. */
    public static final int NO_STANDOFF = -1;

    /**
     * @param awayFromWall {@code awayfromwall}: how far the mover is held off a
     *                     wall, as a shift of the line's own normal. Nought for
     *                     the player, one for the things that walk, and negative
     *                     for a bullet, which is held off nothing.
     */
    public void move(int fromZone, int oldX, int oldZ, int newX, int newZ,
                     int oldY, int newY, int extLen, int thingHeight, int stepUp,
                     int stepDown, int flags, int awayFromWall) {
        this.away = awayFromWall;
        this.moveOldY = oldY;
        this.moveNewY = newY;
        // move.b PLR1_StoodInTop,StoodInTop before the call and
        // move.b StoodInTop,PLR1_StoodInTop after it: the flag is carried in and
        // out, and only a crossing changes it. Without seeding it, a move that
        // crosses nothing reports whatever the last crossing anywhere left.
        x = newX;
        z = newZ;
        zone = fromZone;
        hitWall = false;
        passes = 0;

        // tst.w xdiff / tst.w zdiff / rts
        if (newX == oldX && newZ == oldZ) {
            return;
        }

        int quit = QUIT_OUT;
        while (quit-- > 0) {
            passes++;
            if (zone < 0 || zone >= lv.zones.length) {
                return;
            }
            Zone room = lv.zone(zone);

            for (int e : room.exitLines) {          // checkwalls
                if (e < 0 || e >= lv.floorLines.length) {
                    continue;
                }
                FloorLine fl = lv.floorLine(e);

                int lowerFloor = NO_HEIGHT, lowerRoof = NO_HEIGHT;
                int upperFloor = NO_HEIGHT, upperRoof = NO_HEIGHT;
                int to = fl.toZone;
                if (to >= 0 && to < lv.zones.length) {
                    Zone t = lv.zone(to);
                    lowerFloor = t.floorHeight;     // ToZoneFloor(a4)
                    lowerRoof = t.roofHeight;
                    upperFloor = t.upperFloorHeight;
                    upperRoof = t.upperRoofHeight;
                }

                // The cross product of the move against the line, with the
                // line fattened by the mover's own standoff
                int a4 = standoffX(fl);
                int a6 = standoffZ(fl);
                int d0 = M68k.w(M68k.w(x - fl.x1) - a4);
                int d1 = M68k.w(M68k.w(z - fl.z1) - a6);
                int d2 = M68k.w(M68k.w(fl.dx - a4) - a6);
                int d5 = M68k.w(M68k.w(fl.dz + a4) - a6);
                int cross = M68k.muls(d5, d0) - M68k.muls(d2, d1);
                int len = M68k.w(fl.length + extLen);
                if (len == 0) {
                    continue;
                }

                if (cross > 0) {
                    // Inside: near enough to touch, but never blocked
                    int d = M68k.w(M68k.divsInto(cross, len));
                    if (d < TOUCH) {
                        flag(fl, flags);
                    }
                    continue;                        // oknothitwall
                }

                beyond++;
                // chkhttt: past the line, so the height decides
                int d7 = M68k.w(M68k.divsInto(cross, len));
                int otherCross = M68k.muls(d5, M68k.w(M68k.w(oldX - fl.x1) - a4))
                               - M68k.muls(d2, M68k.w(M68k.w(oldZ - fl.z1) - a6));
                int otherD = M68k.w(M68k.divsInto(otherCross, len));
                int span = M68k.w(otherD - d7);
                if (span <= 0) {
                    span = 1;                        // .ohbugger
                }

                int dy = newY - oldY;
                int cross1 = dy;
                if (dy != 0) {
                    cross1 = M68k.muls(M68k.w(M68k.divsInto(dy, span)), d7);
                }
                cross1 += newY;                      // height at the crossing
                int d6 = cross1 + thingHeight - stepUp;

                boolean hit;
                if (d6 >= lowerFloor) {              // bge .yeshit
                    hit = true;
                } else if (cross1 > lowerRoof) {     // bgt oknothitwall
                    hit = false;
                } else if (cross1 < upperRoof) {     // blt .yeshit
                    hit = true;
                } else {
                    hit = d6 >= upperFloor;          // blt oknothitwall
                }
                if (!hit) {
                    byHeight++;
                    continue;
                }

                // .calcalong: put the point back on the line
                int px = M68k.w(-M68k.w(M68k.divsInto(M68k.muls(d7, d5), len)) + x);
                int pz = M68k.w(M68k.w(M68k.divsInto(M68k.muls(d7, d2), len)) + z);

                // othercheck: only if that point is actually on the segment
                int rx = M68k.w(px - fl.x1);
                int rz = M68k.w(pz - fl.z1);
                if (!onSegment(rx, rz, d2, d5)) {
                    bySegment++;
                    continue;                        // oknothitwall
                }

                slid++;
                x = px;                              // hitthewall
                z = pz;
                flag(fl, flags);
                hitWall = true;
            }

            // nomorewalls: the second pass, over the whole list this time
            otherWalls(room, oldX, oldZ, newY, extLen, thingHeight, stepUp,
                       stepDown, flags);

            // gobackanddoitallagain is reached only after the point has crossed
            // into a new room -- the wall pass over one room runs once. Repeating
            // it while anything moved makes the slides chase each other instead
            // of settling, and the point drifts out of the room entirely.
            int next = crossedInto(zone, oldX, oldZ);
            if (next == zone) {
                settle(fromZone, oldX, oldZ);
                return;
            }
            zone = next;
        }
        settle(fromZone, oldX, oldZ);
    }

    /**
     * Keeps the move only if it ended somewhere real.
     *
     * {@code checkwalls} is the first of two passes: {@code checkotherwalls} and
     * the block after it handle the cases this one lets through, chiefly a point
     * that has gone round the end of a wall rather than through it. Those are not
     * transcribed, and without them the point can finish inside no room at all --
     * which the renderer would draw as the world seen from outside. Rather than
     * approximate that pass, a move that lands nowhere is refused.
     */
    private void settle(int fromZone, int oldX, int oldZ) {
        if (!contains(zone)) {
            refused++;
            if (REFUSE_STRAY) {
                x = oldX;
                z = oldZ;
                zone = fromZone;
            }
        }
    }

    /**
     * {@code checkotherwalls}: the second pass.
     *
     * It differs from the first in three ways that matter. It walks the whole
     * exit list rather than stopping at the first negative. Its height tests
     * actually run -- the first pass jumps over the same code -- so this is where
     * headroom, step up and step down are decided. And rather than asking where
     * the end point landed, it intersects the <em>path</em> with the line
     * segment, which catches a move that went round the end of a wall instead of
     * through it. On a hit the point is put on the wall pulled three units back.
     */
    private void otherWalls(Zone room, int oldX, int oldZ, int newY, int extLen,
                            int thingHeight, int stepUp, int stepDown, int flags) {
        if (extLen == 0) {
            return;                              // tst.w extlen / beq
        }
        for (int e : room.allExitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);

            p2seen++;
            if (!blocks(fl, newY, thingHeight, stepUp, stepDown)) {
                p2open++;
                continue;                        // bra checkotherwalls
            }

            // the same standoff the first pass uses -- this pass shears the
            // line by a4 and a6 exactly as thisisawall2 does, and leaving it out
            // here undoes what the first pass did
            int a4 = standoffX(fl);
            int a6 = standoffZ(fl);
            int dxl = M68k.w(M68k.w(fl.dx - a4) - a6);
            int dzl = M68k.w(M68k.w(fl.dz + a4) - a6);

            // The end point must be strictly past the line: bge .oknothitwall
            int cross = M68k.muls(dzl, M68k.w(M68k.w(x - fl.x1) - a4))
                      - M68k.muls(dxl, M68k.w(M68k.w(z - fl.z1) - a6));
            if (cross >= 0) {
                p2notPast++;
                continue;
            }
            int d7 = cross;

            // Does the path actually cross the segment? The numerator and
            // denominator of the crossing parameter, which must lie in [0,1].
            int g = M68k.w(x - oldX);
            int h = M68k.w(z - oldZ);
            int ea = M68k.w(oldX - fl.x1);
            int bf = M68k.w(fl.z1 - oldZ);
            long num = (long) M68k.muls(h, ea) + M68k.muls(g, bf);
            long den = (long) M68k.muls(h, dxl) - M68k.muls(g, dzl);
            if (den == 0) {
                p2noStraddle++;
                continue;
            }
            if (den > 0) {                       // .botpos
                if (num < 0 || den < num) {
                    p2noStraddle++;
                    continue;
                }
            } else {                             // .botneg
                if (num > 0 || den > num) {
                    p2noStraddle++;
                    continue;
                }
            }

            // .mighthit: the point on the wall, pulled back three units
            int len = M68k.w(fl.length + extLen);
            if (len == 0) {
                continue;
            }
            int d = M68k.w(M68k.w(M68k.divsInto(d7, len)) - 3);
            int px = M68k.w(-M68k.w(M68k.divsInto(M68k.muls(d, dzl), len)) + x);
            int pz = M68k.w(M68k.w(M68k.divsInto(M68k.muls(d, dxl), len)) + z);

            // The old position had to be on the inside: blt .oknothitwall
            int oldCross = M68k.muls(dzl, M68k.w(oldX - fl.x1))
                         - M68k.muls(dxl, M68k.w(oldZ - fl.z1));
            if (oldCross < 0) {
                p2oldOutside++;
                continue;
            }

            p2slid++;
            slid++;
            x = px;                              // .hitthewall
            z = pz;
            flag(fl, flags);
            hitWall = true;
        }
    }

    /**
     * The height tests of {@code anotherwalls}, which this pass really runs.
     *
     * A line into another room is a wall when the room is too low to stand in,
     * when its floor is more than a step up or a fall away, or when its roof
     * would come through the mover's head. Each is asked of the lower storey
     * first and then of the upper one, so a doorway can be blocked below and open
     * above.
     */
    private boolean blocks(FloorLine fl, int newY, int thingHeight,
                           int stepUp, int stepDown) {
        int to = fl.toZone;
        if (to < 0 || to >= lv.zones.length) {
            return true;                         // blt .thisisawall2: solid
        }
        Zone t = lv.zone(to);
        if (!barred(newY, thingHeight, stepUp, stepDown,
                    t.floorHeight, t.roofHeight)) {
            return false;
        }
        return barred(newY, thingHeight, stepUp, stepDown,
                      t.upperFloorHeight, t.upperRoofHeight);
    }

    /** One storey's worth of the same test. */
    private static boolean barred(int newY, int thingHeight, int stepUp,
                                  int stepDown, int floor, int roof) {
        if (floor - roof <= thingHeight) {
            return true;                         // ble: no room to stand
        }
        int d1 = newY + thingHeight - floor;
        if (d1 > 0) {                            // bgt .chkstepup
            if (d1 >= stepUp) {
                return true;                     // too far up
            }
        } else if (-d1 >= stepDown) {
            return true;                         // too far down
        }
        return newY - roof < 0;                  // blt: head through the roof
    }

    /**
     * {@code okplus1} to {@code zispos}: is the projected point between the ends?
     *
     * The test runs on whichever axis the line is longer along, which avoids
     * dividing by a near-zero component, and allows four units past each end.
     */
    private static boolean onSegment(int rx, int rz, int dx, int dz) {
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        if (az > ax) {
            if (rz > 0) {
                int lim = M68k.w(dz);
                return lim >= -SLACK && rz <= M68k.w(lim + SLACK);
            }
            int lim = M68k.w(dz);
            return lim <= SLACK && rz >= M68k.w(lim - SLACK);
        }
        if (rx > 0) {
            int lim = M68k.w(dx);
            return lim >= -SLACK && rx <= M68k.w(lim + SLACK);
        }
        int lim = M68k.w(dx);
        return lim <= SLACK && rx >= M68k.w(lim - SLACK);
    }

    /**
     * {@code CheckMoreFloorLines}: which room the move crossed into.
     *
     * Two things have to hold, and the second is what a half-plane test misses.
     * The end point must be past the line -- {@code okthebottom}, the cross
     * product of the new position against it. And the path must pass
     * <em>between the line's two ends</em>: {@code checkifcrossed} takes the
     * movement vector against each end in turn and requires them to fall on
     * opposite sides. Without that second test a move that goes round the end of
     * a wall is read as having gone through it, and the mover is handed a room it
     * never entered.
     *
     * The height blocks either side of this are commented out in the shipped
     * build, so the decision is purely geometric.
     */
    private int crossedInto(int from, int oldX, int oldZ) {
        Zone room = lv.zone(from);
        int g = M68k.w(x - oldX);
        int h = M68k.w(z - oldZ);
        for (int e : room.exitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);
            if (fl.toZone < 0 || fl.toZone >= lv.zones.length) {
                continue;                        // tst.w 8(a2) / blt
            }
            int dxl = M68k.w(fl.dx);
            int dzl = M68k.w(fl.dz);

            // okthebottom: is the end point past the line?
            int cross = M68k.muls(dzl, M68k.w(x - fl.x1))
                      - M68k.muls(dxl, M68k.w(z - fl.z1));
            if (cross >= 0) {
                continue;                        // bge StillSameSide
            }

            // checkifcrossed: the two ends must straddle the path
            long first = (long) M68k.muls(M68k.w(fl.x1 - oldX), h)
                       - M68k.muls(M68k.w(fl.z1 - oldZ), g);
            if (first > 0) {
                continue;                        // bgt StillSameSide
            }
            long second = (long) M68k.muls(M68k.w(M68k.w(fl.x1 + dxl) - oldX), h)
                        - M68k.muls(M68k.w(M68k.w(fl.z1 + dzl) - oldZ), g);
            if (second < 0) {
                continue;                        // blt StillSameSide
            }
            // Find height at crossing point.
            //
            // The original interpolates: it divides the cross product at each
            // end by the line's length, takes the difference as a span, and
            // moves the height that far along it. But the block reads
            // {@code sub.w a4,d0} for the old end, and a4 has been the pointer
            // to the destination zone since the top of the loop -- the address
            // registers are never put back between. So the span it divides by is
            // built from the low word of an address, and the term that scales
            // the height by it is not a number anyone chose. What it degenerates
            // to is the height at the end of the move, which is what is used
            // here; the oddity is recorded rather than reproduced, because
            // reproducing it would mean reproducing where the level data
            // happened to be loaded.
            //
            // cmp.l LowerRoofHeight,d4 / slt StoodInTop: above the roof of the
            // room being entered means the upper storey of it.
            Zone dest = lv.zone(fl.toZone);
            stoodInTop = moveNewY < dest.roofHeight;
            return fl.toZone;
        }
        return from;
    }

    /**
     * {@code thisisawall2}: how far a mover is held off a line.
     *
     * Bytes twelve and thirteen of a line record are its own normal, and the
     * routine subtracts them from the point before taking the cross product --
     * and shears the line's direction by them as well -- so the line is
     * effectively fattened by the mover's radius rather than the point being
     * given one. {@code awayfromwall} shifts the normal up, so a thing that
     * walks is held off twice as far as the player, and a bullet, whose
     * {@code awayfromwall} is negative, is held off nothing at all and can touch
     * the wall it hits.
     *
     * Without this the player walks until their centre is on the line, which
     * puts them inside the wall: the wall's own surface is then behind the near
     * plane, the clip throws it away, and the room is seen from within its own
     * masonry.
     */
    private int standoffX(FloorLine fl) {
        return away < 0 ? 0 : M68k.w(fl.ox << away);
    }

    private int standoffZ(FloorLine fl) {
        return away < 0 ? 0 : M68k.w(fl.oz << away);
    }

    /** {@code awayfromwall}, for the move being resolved. */
    private int away = NO_STANDOFF;
    /** The heights the move runs between, for the storey test at a crossing. */
    private int moveOldY, moveNewY;

    /** Is the point on the inner side of every one of a room's lines? */
    private boolean contains(int zi) {
        if (zi < 0 || zi >= lv.zones.length) {
            return false;
        }
        Zone room = lv.zone(zi);
        if (room.exitLines.length == 0) {
            return false;
        }
        for (int e : room.exitLines) {
            if (e >= 0 && e < lv.floorLines.length && lv.floorLine(e).side(x, z) < 0) {
                return false;
            }
        }
        return true;
    }

    /** How many rooms a single step may pass through. */
    private static final int MAX_CROSSINGS = 16;

    /** {@code move.w wallflags,d0 / or.w d0,14(a2)}. */
    private void flag(FloorLine fl, int flags) {
        int at = fl.offset + 14;
        lv.data.setS16(at, lv.data.s16(at) | flags);
    }
}
