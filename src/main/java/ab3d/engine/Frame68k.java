package ab3d.engine;

import ab3d.data.BinReader;
import ab3d.data.FloorTexture;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.SpriteBank;
import ab3d.data.WadTexture;
import ab3d.data.WallTextures;
import ab3d.data.Zone;
import ab3d.m68k.M68k;

import java.io.IOException;

/**
 * The frame driver: the room loop of source/master.s that runs from
 * {@code subroomloop} to {@code dothisroom}, with every routine it calls.
 *
 * The shape of a frame is: rotate the visible points once, order the zones, then
 * walk that order <em>backwards</em> -- {@code move.w -(a0),d7} from
 * {@code endoflist} -- so the furthest zone is drawn first and nearer ones paint
 * over it. There is no depth buffer anywhere in this engine; the order is the
 * whole of the hidden-surface removal.
 *
 * A zone can appear in the graphics list more than once, and each appearance
 * carries its own clip list: {@code finditit} keeps scanning after it has drawn
 * one, so the same room seen through two doorways is drawn twice through two
 * different windows. Missing that would draw such a room once, through whichever
 * opening happened to come first.
 */
public final class Frame68k {

    private final EngineState s;
    private final Level lv;
    private final BinReader g;

    private final Renderer68k rotate;
    private final OrderZones order;
    private final WallDraw wallDraw;
    private final WallSubdivide subdivide = new WallSubdivide();
    private final CalcAndDraw calc;
    private final ScreenDivide divide;
    private final StripDraw strip;
    private final FloorDraw floor;
    private final BitMapObj bitmap;
    private final ObjDraw objects;
    private final GunDraw gun;
    private final Backdrop backdrop;
    /** {@code BrightAnimTable} and the routine that advances it. */
    public final BrightAnim brightAnim = new BrightAnim();
    /** The level's doors, whose zones this drives. */
    public final Doors doors;
    /** The level's lifts, whose zone floors this drives. */
    public final Lifts lifts;
    /** The level's eight switches, which arm the condition bits. */
    public final Switches switches;
    /** {@code ObjectHandler}: the pickups that only wait to be walked into. */
    public final ObjectHandler objectHandler;
    /** {@code PLR1_GunData}: eight guns, their ammunition and what they do. */
    public final ab3d.data.GunData gunData;
    /** {@code Player1Shot} and the table its aim reads. */
    public final PlayerShot shot;
    /** {@code GunAnims}, which says how long each weapon's animation is. */
    public final ab3d.data.GunAnims gunAnims;
    /** The routines every enemy shares, and the one enemy that uses them. */
    public final AlienControl alienControl;
    public final Enemy enemies;
    /** The two pools of twenty shots, and the routine that flies them. */
    public final Bullets bullets;
    /** {@code ItsABarrel}, which explodes rather than moves. */
    public final Barrel barrel;

    /**
     * Where every {@code jsr MakeSomeNoise} in the engine goes.
     *
     * Handing it to each routine keeps them able to run in a check with no
     * audio at all, which is how every one of them has been tested so far.
     */
    public void setSound(Sound s) {
        doors.sound = s;
        lifts.sound = s;
        switches.sound = s;
        objectHandler.sound = s;
        shot.sound = s;
        enemies.sound = s;
        barrel.sound = s;
        bullets.sound = s;
    }
    /** {@code Conditions}: the bits the switches and keys have set so far. */
    public int conditions;

    private final WallTextures wallTextures;
    private final FloorTexture floorTexture;
    private final ab3d.data.WaterTexture waterTexture;
    private final SineTable sine;

    /** Counters for one frame. */
    public int zonesDrawn, zonesClippedOut, wallsDrawn, surfacesDrawn, spritesDrawn;
    public int wallRows, floorPixels, spritePixels;
    /** Why walls and surfaces were rejected, for tracing a blank frame. */
    public final java.util.Map<String, Integer> rejects = new java.util.TreeMap<>();
    public int wallsSeen, surfacesSeen, objectsSeen;
    /** Wall endpoints that were not in the list the frame rotated. */
    public int pointsNotRotated, pointsChecked;
    public int cornersNotRotated, cornersChecked;
    public int floorBlack, floorIndexZero;
    /** Connect-table reads that fell outside the table. */
    public int connectOutOfRange, connectReads;
    /** Columns some zone's clip window covered this frame. */
    public final boolean[] columnCovered = new boolean[EngineState.VIEW_COLUMNS];
    /** The clip windows used, for tracing a gap between them. */
    public final java.util.List<int[]> clipWindows = new java.util.ArrayList<>();
    public final java.util.Map<Integer, Integer> floorTiles = new java.util.TreeMap<>();
    public final java.util.Map<Integer, Integer> floorShades = new java.util.TreeMap<>();
    private boolean[] rotatedThisFrame;

    private void note(String why) {
        rejects.merge(why, 1, Integer::sum);
    }

    public Frame68k(Level level, GameData game) throws IOException {
        this.lv = level;
        this.g = level.graphics.data;
        this.s = new EngineState(level);
        this.rotate = new Renderer68k(s);
        this.order = new OrderZones(level);
        this.wallDraw = new WallDraw(s);
        this.calc = new CalcAndDraw(s);
        this.divide = new ScreenDivide(s, game);
        this.strip = new StripDraw(s, game);
        this.floor = new FloorDraw(s);
        SpriteBank sprites = new SpriteBank(game);
        this.bitmap = new BitMapObj(s, sprites, game);
        this.objects = new ObjDraw(s, bitmap);
        this.gunAnims = ab3d.data.GunAnims.load(game);
        this.gun = new GunDraw(s, sprites, this.gunAnims);
        this.backdrop = new Backdrop(s, game);
        this.doors = new Doors(level);
        this.lifts = new Lifts(level);
        this.switches = new Switches(level);
        this.gunData = ab3d.data.GunData.load(game);
        this.gunData.defaultGame();
        this.objectHandler = new ObjectHandler(level);
        this.objectHandler.guns = this.gunData;
        this.wallTextures = new WallTextures(game);
        this.floorTexture = FloorTexture.load(game);
        this.waterTexture = ab3d.data.WaterTexture.load(game);
        this.sine = SineTable.load(game);
        this.shot = new PlayerShot(level, this.sine,
                                   ab3d.data.ColBox.load(game), this.gunData);
        this.alienControl = new AlienControl(level, this.sine);
        this.enemies = new Enemy(level, this.alienControl, new MoveObject(level),
                                 new Enemy.Rand());
        this.objectHandler.enemies = this.enemies;
        ab3d.data.ColBox boxes = ab3d.data.ColBox.load(game);
        this.bullets = new Bullets(level, new MoveObject(level), boxes,
                                   ab3d.data.BulletAnims.load(game),
                                   this.alienControl);
        this.objectHandler.bullets = this.bullets;
        this.objectHandler.gunData = this.gunData;
        this.shot.bullets = this.bullets;
        this.enemies.bullets = this.bullets;
        this.enemies.guns = this.gunData;
        this.enemies.sine = this.sine;
        this.barrel = new Barrel(level, this.alienControl, this.bullets);
        this.objectHandler.barrel = this.barrel;
        this.floor.setSineTable(this.sine);
    }

    /**
     * {@code GOURSEL}, which {@code Start:} sets in the RTG build. Turning it off
     * gives the flat floor of the next detail level down, which is the mode
     * master.s only has.
     */
    public boolean gouraudFloor = true;
    /** {@code wtan}: the water ripple phase, advanced once a frame. */
    public int wtan;
    /** {@code PLR1_GunSelected} and {@code PLR1_GunFrame}. */
    public int gunSelected, gunFrame;
    /**
     * The look-behind key.
     *
     * {@code DrawDisplay} negates both the sine and the cosine, which turns the
     * view a half circle without moving the player, and sets {@code DONTDOGUN}
     * so the weapon is not drawn over a view the player is not facing. The zone
     * order is untouched: {@code OrderZones} runs before this and uses only the
     * position, so a half turn cannot change which rooms are visible.
     */
    public boolean lookBehind;
    /** {@code DONTDOGUN}: set while looking behind. */
    public boolean noGun;
    public int gunPixels, backdropPixels;
    /** {@code wateroff}: the pattern drift, +1 a frame, sixty-four wide. */
    public int waterOff;

    /** Wall rows written per column, for tracing a gap between walls. */
    public int[] wallRowsPerColumn() {
        return strip.rowsPerColumn;
    }

    /** {@code DoorRoutine}: moves every door by one frame's worth. */
    public void updateDoors(int frames, boolean spaceTapped) {
        doors.update(frames, spaceTapped, conditions);
    }

    /** {@code LiftRoutine}, called from {@code objmoveanim} beside the doors. */
    public void updateLifts(int frames, int viewerZone, boolean spaceTapped) {
        lifts.update(frames, viewerZone, spaceTapped, conditions);
    }

    /**
     * {@code SwitchRoutine}, which runs before the doors in {@code objmoveanim}
     * -- so a switch pressed this frame is already armed when they are tested.
     */
    public void updateSwitches(int frames, int px, int pz, boolean spaceTapped) {
        conditions = switches.update(conditions, frames, px, pz, spaceTapped);
    }

    /**
     * {@code Player1Shot}, which {@code objmoveanim} calls first of all -- before
     * the switches, the doors, the lifts and the objects.
     *
     * {@code CalcPLR1InLine} runs earlier still, in the frame's own setup, so the
     * aim is worked out against where things were drawn rather than where they
     * are about to move to.
     */
    public boolean firePlayer(boolean fire, boolean clicked, int frames,
                              int px, int pz, int playerY, int playerHeight,
                              int angle) {
        return firePlayer(fire, clicked, frames, px, pz, playerY, playerHeight,
                          angle, 0, false);
    }

    public boolean firePlayer(boolean fire, boolean clicked, int frames,
                              int px, int pz, int playerY, int playerHeight,
                              int angle, int playerZone, boolean playerInTop) {
        shot.calcInLine(px, pz, sine.sin(angle), sine.cos(angle));
        shot.dirX = sine.sin(angle);               // tempxdir
        shot.dirZ = sine.cos(angle);
        shot.playerZone = playerZone;
        shot.playerInTop = playerInTop;
        boolean went = shot.fire(gunSelected, fire, clicked, frames, px, pz,
                                 playerY, playerHeight,
                                 gunAnims.maxFrame(gunSelected));
        gunFrame = shot.gunFrame;
        return went;
    }

    /**
     * {@code ObjectHandler}, the last of the four routines {@code objmoveanim}
     * calls -- so a door tested this frame has not yet seen a key taken in it.
     */
    public void updateObjects(int viewerZone, int px, int pz, int py,
                              boolean inTop, int frames, int angle) {
        // The player is an object like any other, and enemy fire hits them
        // through the same loop it hits an enemy with -- so their record has to
        // carry where they are before anything moves.
        placePlayer(px, pz, py, viewerZone, inTop);
        objectHandler.frames = Math.max(1, frames);
        objectHandler.playerY = py;
        enemies.tickWalk();                      // move.l (a0)+,alframe
        objectHandler.sinval = sine.sin(angle);
        objectHandler.cosval = sine.cos(angle);
        conditions = objectHandler.update(viewerZone, px, pz, inTop, conditions);
    }

    /**
     * {@code USEPLR1}'s first job: what has been done to the player since last
     * frame.
     *
     * {@code move.b damagetaken(a0),d2 / sub.w d2,PLR1_energy}, and the screen's
     * border flashes red -- {@code move.w #$f00,hitcol} -- for the frame it
     * happens in. The damage is cleared whether or not any was taken.
     *
     * @return the energy after it, never below nothing
     */
    public int usePlayer(int energy) {
        int hurt = enemies.playerDamage + playerRecordDamage();
        enemies.playerDamage = 0;                  // move.b #0,damagetaken(a0)
        hitFlash = hurt != 0;
        return Math.max(0, energy - hurt);
    }

    /** {@code hitcol}: set for the frame the player is hurt in. */
    public boolean hitFlash;

    /**
     * {@code USEPLR1}'s other half: the player's own object record.
     *
     * {@code move.l PLR1_xoff,(a1,d0.w*8)} puts them at their point,
     * {@code move.b #5,16(a0)} gives the record the type byte for player one,
     * and {@code move.b PLR1_energy+1,numlives(a0)} keeps its lives in step --
     * which matters because every routine that looks for something to hurt skips
     * a record with no lives left.
     */
    private void placePlayer(int px, int pz, int py, int zone, boolean inTop) {
        int base = lv.ptrPlr1Obj;
        if (!lv.data.inRange(base, Obj.SIZE)) {
            return;
        }
        int pt = lv.data.s16(base + Obj.POINT);
        if (pt >= 0 && pt < lv.objectPointX.length) {
            lv.objectPointX[pt] = px;
            lv.objectPointZ[pt] = pz;
        }
        lv.data.setU8(base + Obj.TYPE, 5);
        lv.data.setS16(base + Obj.ZONE, zone);
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
        lv.data.setU8(base + Obj.IN_TOP, inTop ? 0xff : 0);
        lv.data.setS16(base + Obj.HEIGHT, M68k.w(py >> 7));
        lv.data.setU8(base + Obj.NUM_LIVES,
                      Math.max(1, objectHandler.energy) & 0xff);
    }

    /** What enemy fire has done to the player, read back from their record. */
    private int playerRecordDamage() {
        int base = lv.ptrPlr1Obj;
        if (!lv.data.inRange(base, Obj.SIZE)) {
            return 0;
        }
        int hurt = lv.data.u8(base + Obj.DAMAGE_TAKEN);
        lv.data.setU8(base + Obj.DAMAGE_TAKEN, 0);
        return hurt;
    }

    /**
     * The {@code worry} loop, which jg.s runs <em>after</em> {@code objmoveanim}
     * rather than before it. An object therefore becomes eligible one frame
     * after it comes into view, and that lag is the original's, not an accident
     * of the port.
     */
    public void armObjects(int viewerZone) {
        if (viewerZone < 0 || viewerZone >= lv.zones.length) {
            return;
        }
        objectHandler.arm(lv.zone(viewerZone));
    }

    public EngineState state() {
        return s;
    }

    /**
     * Draws one frame from the given viewpoint.
     *
     * @param zoneIndex the zone the eye is in
     * @param angle     the facing, in the sine table's 4096 units to the turn
     * @param eyeY      {@code yoff}, in Y units
     */
    public void render(int zoneIndex, int x, int z, int eyeY, int angle) {
        zonesDrawn = 0;
        zonesClippedOut = 0;
        wallsDrawn = 0;
        surfacesDrawn = 0;
        spritesDrawn = 0;
        wallRows = 0;
        floorPixels = 0;
        spritePixels = 0;
        wallsSeen = 0;
        surfacesSeen = 0;
        objectsSeen = 0;
        rejects.clear();
        wtan = (wtan + 640) & 8191;            // add.w #640,wtan / and.w #8191
        waterOff = (waterOff + 1) & 63;        // add.w #1,wateroff / and.w #63
        brightAnim.tick();                     // bsr brightanim, once a frame
        java.util.Arrays.fill(s.screen, (short) 0);

        s.xoff = x;
        s.zoff = z;
        s.yoff = eyeY;
        s.sinval = sine.sin(angle);
        s.cosval = sine.cos(angle);
        if (lookBehind) {
            s.cosval = -s.cosval;              // neg.w cosval
            s.sinval = -s.sinval;              // neg.w sinval
        }
        noGun = lookBehind;                    // sne DONTDOGUN
        s.deriveYoffs();

        // xwobxoff and xwobzoff are the head sway resolved along the view, not
        // a function of position: move.w sinval,d1 / muls d3,d1 / swap / asr.w #7,
        // and the same through cosval, negated. d3 is the sway itself, so with no
        // bob both come out zero. Deriving them from xoff and zoff instead
        // anchors the floor to three quarters of the position and makes it slide
        // sideways as the viewer walks.
        int sway = s.xwobble;
        s.xwobXoff = M68k.w(M68k.asrW(M68k.swap(M68k.muls(M68k.w(s.sinval), sway)), 7));
        s.xwobZoff = M68k.w(-M68k.w(
                M68k.asrW(M68k.swap(M68k.muls(M68k.w(s.cosval), sway)), 7)));

        // doneallz: rebuild CurrentPointBrights for this frame and this storey
        if (s.currentPointBright == null) {
            s.currentPointBright = new int[lv.numPoints];
        }
        for (int i = 0; i < lv.numPoints; i++) {
            s.currentPointBright[i] = brightAnim.resolvePoint(lv.pointBrightRaw[i]);
        }

        Zone here = lv.zone(zoneIndex);

        // tst.w (a0)+ / beq nobackgraphics / jsr putinbackdrop: the zone the
        // viewer stands in decides, and it goes down before any room
        backdropPixels = 0;
        if (here.drawBackdrop != 0) {
            backdrop.draw(lookBehind ? (angle + 2048) * 2 : angle * 2);
            backdropPixels = backdrop.pixelsWritten;
        }

        if (rotatedThisFrame == null) {
            rotatedThisFrame = new boolean[lv.numPoints];
        }
        java.util.Arrays.fill(rotatedThisFrame, false);
        for (int pt : here.points) {
            if (pt >= 0 && pt < rotatedThisFrame.length) {
                rotatedThisFrame[pt] = true;
            }
        }
        pointsNotRotated = 0;
        pointsChecked = 0;
        cornersNotRotated = 0;
        cornersChecked = 0;
        floorBlack = 0;
        floorIndexZero = 0;
        connectOutOfRange = 0;
        connectReads = 0;
        java.util.Arrays.fill(columnCovered, false);
        java.util.Arrays.fill(strip.rowsPerColumn, 0);
        clipWindows.clear();
        floorTiles.clear();
        floorShades.clear();
        rotate.rotateLevelPts(here.points);
        rotate.rotateObjectPts();
        order.run(here.graphics, s.xoff, s.zoff);

        // subroomloop: move.w -(a0),d7, from endoflist backwards
        for (int i = order.orderCount - 1; i >= 0; i--) {
            int zoneNumber = order.finalOrder[i];
            if (zoneNumber < 0 || zoneNumber >= lv.zones.length) {
                break;                              // blt jumpoutofrooms
            }
            Zone target = lv.zone(zoneNumber);
            int splitHeight = target.roofHeight;

            int graphAt = lv.graphics.zoneGraphOffsetsOffset + zoneNumber * 8;
            int lower = g.inRange(graphAt, 8) ? g.s32(graphAt) : 0;
            int upper = g.inRange(graphAt, 8) ? g.s32(graphAt + 4) : 0;

            // finditit: every appearance of this zone, each with its own clips
            for (int e = 0; e < here.graphics.size(); e++) {
                if (here.graphics.get(e).graphNumber() != zoneNumber) {
                    continue;
                }
                if (!applyClips(here, e)) {
                    zonesClippedOut++;
                    continue;
                }
                zonesDrawn++;
                clipWindows.add(new int[]{s.leftClip, s.rightClip, zoneNumber});
                for (int c = Math.max(0, s.leftClip);
                     c < Math.min(EngineState.VIEW_COLUMNS, s.rightClip); c++) {
                    columnCovered[c] = true;
                }
                if (s.yoff >= splitHeight) {
                    if (upper > 0) {
                        doThisRoom(upper, true);
                    }
                    doThisRoom(lower, false);
                } else {
                    doThisRoom(lower, false);
                    if (upper > 0) {
                        doThisRoom(upper, true);
                    }
                }
            }
        }

        // jumpoutofrooms: the weapon goes on last, over everything
        if (!noGun) {
            gun.draw(gunSelected, gunFrame);
            // NOGUNLOOK: the frame counts down after the draw, not before, so a
            // shot shows its first frame in the frame it was fired
            shot.ageGunFrame(1);
            gunFrame = shot.gunFrame;
            gunPixels = gun.pixelsWritten;
        } else {
            gunPixels = 0;
        }
    }

    /**
     * Sets the clip window for one appearance of a zone.
     *
     * The clip list is a run of left clips, a skipped word, then a run of right
     * clips, each ended by a negative. An entry names a point; the routines
     * reject it when that point is behind the eye, and otherwise narrow the
     * window to its projected column -- but only after checking against the
     * connect table that the point really is the near end of its edge, which is
     * what stops a doorway clipping the room it is a doorway into.
     *
     * @return false when nothing of the zone can be seen through this opening
     */
    private boolean applyClips(Zone from, int entry) {
        s.leftClip = 0;                             // move.w #0,leftclip
        s.rightClip = EngineState.VIEW_COLUMNS;     // move.w #96,rightclip

        int at = from.clipAt[entry];
        if (at >= 0) {
            // intolcliplop: tst.w (a0) / blt outoflcliplop. Any negative ends the
            // run -- and the two runs are separated by -1, with -2 ending the
            // list, so continuing through -1 reads the right-hand clips as
            // left-hand ones and then takes the right-hand ones from the next
            // zone's list entirely.
            while (lv.clips.inRange(at * 2, 2) && lv.clips.s16(at * 2) >= 0) {
                setLeftClip(lv.clips.s16(at * 2));
                at++;
            }
            at++;                                   // addq #2,a0 past the marker
            while (lv.clips.inRange(at * 2, 2) && lv.clips.s16(at * 2) >= 0) {
                setRightClip(lv.clips.s16(at * 2));
                at++;
            }
        }

        // cmp.w #95,d0 / bge / tst rightclip / blt / cmp.w d1,d0 / bge
        return s.leftClip < EngineState.VIEW_COLUMNS - 1
                && s.rightClip >= 0
                && s.leftClip < s.rightClip;
    }

    /**
     * {@code NEWsetlclip}.
     *
     * Every path here advances the list pointer and returns, so a clip point
     * behind the eye is skipped and the run carries on. Ending the run there
     * would drop every clip after it and leave the window too wide.
     */
    private void setLeftClip(int point) {
        if (point < 0 || point >= s.rotatedZ.length) {
            return;                                 // .leftnotoktoclip
        }
        if (s.depth(point) <= 0) {
            return;                                 // behind the eye: rts
        }
        int d1 = s.onScreen[point];
        int other = connect(point, 2);              // move.w 2(a3,d2.w*4),d2
        if (other >= 0 && other < s.onScreen.length && s.onScreen[other] > d1) {
            return;                                 // .leftnotoktoclip
        }
        if (d1 > s.leftClip) {
            s.leftClip = d1;
        }
    }

    /** {@code NEWsetrclip}. */
    private void setRightClip(int point) {
        if (point < 0 || point >= s.rotatedZ.length || s.depth(point) <= 0) {
            return;
        }
        int d1 = s.onScreen[point];
        int other = connect(point, 0);              // move.w (a3,d2.w*4),d2
        if (other >= 0 && other < s.onScreen.length && s.onScreen[other] < d1) {
            return;
        }
        if (d1 < s.rightClip) {
            s.rightClip = d1 + 1;                   // addq #1,d1
        }
    }

    /**
     * {@code CONNECT_TABLE}: four bytes per point, two point numbers.
     *
     * It lives in the clip file after the last clip list, which is where
     * {@code assignclips} leaves its pointer. The table stops fifteen points
     * short of the point count the header gives in every shipped level, so the
     * read is guarded -- the original would run into whatever follows.
     */
    private int connect(int point, int half) {
        int off = (lv.connectTableAt * 2) + point * 4 + half;
        connectReads++;
        if (!lv.clips.inRange(off, 2)) {
            connectOutOfRange++;
            return -1;
        }
        return lv.clips.s16(off);
    }

    /** {@code dothisroom}: the polygon stream of one room part. */
    private void doThisRoom(int roomOffset, boolean upper) {
        if (roomOffset <= 0 || !g.inRange(roomOffset, 2)) {
            return;
        }
        s.currZone = g.s16(roomOffset);             // move.w (a0)+,d0 / move.w d0,currzone
        s.doUpper = upper;
        Zone zone = s.currZone >= 0 && s.currZone < lv.zones.length
                ? lv.zone(s.currZone) : null;
        // doallz: the word may name an animation rather than be a number
        s.zoneBright = zone == null ? 0
                : brightAnim.resolve(upper ? zone.upperBrightness : zone.brightness);
        // TOPOFROOM / BOTOFROOM: the part of the room this pass draws
        if (zone != null) {
            s.topOfRoom = upper ? zone.upperRoofHeight : zone.roofHeight;
            s.botOfRoom = upper ? zone.upperFloorHeight : zone.floorHeight;
        }

        objects.currZone = s.currZone;
        objects.doUpper = upper;

        PolyLoop.run(g, roomOffset, g.size(), new PolyLoop.Sink() {
            @Override
            public void wall(BinReader gg, int at, boolean seeThrough) {
                drawWall(gg, at);
            }

            @Override
            public void surface(BinReader gg, int at, int type) {
                drawSurface(gg, at, type);
            }

            @Override
            public void object(BinReader gg, int at) {
                objectsSeen++;
                drawObjects(zone, gg.s16(at));
            }
        });
    }

    /** {@code itsawalldraw} and everything downstream of it. */
    private void drawWall(BinReader gg, int at) {
        wallsSeen++;
        for (int pt : new int[]{gg.s16(at), gg.s16(at + 2)}) {
            pointsChecked++;
            if (pt < 0 || pt >= rotatedThisFrame.length || !rotatedThisFrame[pt]) {
                pointsNotRotated++;
            }
        }
        WallDraw.Wall w = wallDraw.read(gg, at);
        if (!w.visible) {
            note("wall:" + w.rejectedBy);
            return;
        }
        if (!subdivide.run(s.rotatedX[w.pointA], s.depth(w.pointA),
                           s.rotatedX[w.pointB], s.depth(w.pointB),
                           w.leftEnd, w.rightEnd,
                           w.leftBright, w.rightBright)) {
            note("wall:subdivide");
            return;
        }
        calc.run(subdivide, w.topOfWall, w.botOfWall, subdivide.multCount);
        WadTexture tex = wallTextures.get(w.textureIndex);
        for (CalcAndDraw.Strip st : calc.strips) {
            if (!divide.run(st)) {
                continue;
            }
            strip.run(divide, tex, w);
            wallRows += strip.rowsWritten;
        }
        wallsDrawn++;
    }

    /** {@code itsafloordraw}, both passes. */
    private void drawSurface(BinReader gg, int at, int type) {
        surfacesSeen++;
        // itsafloor: move.b GOURSEL,gourfloor. GOURSEL is set at Start in the
        // RTG build, so a plain floor or ceiling draws Gouraud unless the detail
        // option has been turned down.
        floor.gouraud = gouraudFloor && type != PolyLoop.T_WATER;
        int sides = gg.s16(at + 2);
        for (int i = 0; i <= sides; i++) {
            int pt = gg.s16(at + 4 + i * 2);
            cornersChecked++;
            if (pt < 0 || pt >= rotatedThisFrame.length || !rotatedThisFrame[pt]) {
                cornersNotRotated++;
            }
        }
        int kind = type == PolyLoop.T_ROOF ? FloorDraw.ROOF : FloorDraw.FLOOR;
        FloorDraw.Setup su = floor.setup(gg, at, kind);
        if (!su.visible) {
            note("surface:" + su.rejectedBy);
            return;
        }
        FloorDraw.Corners c = floor.classifyCorners(gg, at, su);
        if (!FloorDraw.shouldDraw(c)) {
            note("surface:corners");
            return;
        }
        floor.buildSpans(gg, at, su);
        if (!floor.drawIt) {
            note("surface:spans");
            note("  span:bothBehind=" + floor.edgeBothBehind
                 + " badPoint=" + floor.edgeBadPoint
                 + " overflow=" + floor.edgeOverflow
                 + " clipFailed=" + floor.edgeClipFailed
                 + " skipped=" + floor.edgesSkipped
                 + " top=" + floor.top + " bottom=" + floor.bottom);
            return;
        }
        floor.fillRows(su, c);
        if (floor.rows.isEmpty()) {
            note("surface:norows");
            return;
        }

        int after = at + 4 + 2 * (gg.s16(at + 2) + 1);
        int scaleVal = gg.s16(after + 2);
        int whichTile = gg.s16(after + 4);
        int lightType = M68k.w(gg.s16(after + 6) + s.zoneBright);

        // groundfloor: the eye position becomes a 16.16 value before it is
        // scaled -- move.w xoff,d6 / swap d6 / clr.w d6 / ... / move.l d6,sxoff,
        // and sxoff is a dc.l here. master.s keeps it a word and shifts it left
        // by eight instead, which leaves the floor's world anchor short by a
        // factor of sixty-five thousand and makes the texture swim as the
        // viewer moves or turns.
        s.sxoff = scaled(M68k.w(s.xoff + s.xwobZoff) << 16, scaleVal);
        s.szoff = scaled(M68k.w(s.zoff + s.xwobXoff) << 16, scaleVal);

        if (type == PolyLoop.T_WATER) {
            floor.writeIndices(floorTexture, su, scaleVal, whichTile,
                               waterTexture, waterOff);
        } else {
            floor.writeIndices(floorTexture, su, scaleVal, whichTile);
        }
        if (type == PolyLoop.T_WATER) {
            // itswater: the surface ripples what lies under it rather than
            // painting over it, so nothing is drawn here first.
            floor.rippleWater(su, wtan);
            floorPixels += floor.coloursWritten;
            surfacesDrawn++;
            return;
        }
        if (floor.gouraud) {
            floor.convertIndicesGouraud(floorTexture, su);
        } else {
            floor.convertIndices(floorTexture, su, lightType);
        }
        floorPixels += floor.coloursWritten;
        floorBlack += floor.coloursBlack;
        floorIndexZero += floor.indexZero;
        floorTiles.merge(whichTile, 1, Integer::sum);
        floorShades.merge(lightType, 1, Integer::sum);
        surfacesDrawn++;
    }

    /** {@code scaleval}: a signed shift, down for positive and up for negative. */
    private static int scaled(int v, int scaleVal) {
        if (scaleVal == 0) {
            return v;
        }
        return scaleVal > 0 ? M68k.aslL(v, scaleVal) : M68k.asrL(v, -scaleVal);
    }

    /** {@code itsanobject}: the room part selects the sprites' clip heights. */
    private void drawObjects(Zone zone, int part) {
        if (zone == null) {
            return;
        }
        // TOPOFROOM and BOTOFROOM as the room loop left them, so an object on
        // the upper storey is clipped against that storey and not the lower one.
        int top = s.topOfRoom;
        int bottom = s.botOfRoom;
        if (part == ObjDraw.BEFORE_WATER || part == ObjDraw.AFTER_WATER) {
            int water = zone.waterHeight;
            boolean aboveFirst = water >= s.yoff;
            if (part == ObjDraw.BEFORE_WATER) {
                top = aboveFirst ? zone.roofHeight : water;
                bottom = aboveFirst ? water : zone.floorHeight;
            } else {
                top = aboveFirst ? water : zone.roofHeight;
                bottom = aboveFirst ? zone.floorHeight : water;
            }
        }
        objects.run(lv.objects, top, bottom);
        spritesDrawn += objects.drawn;
        spritePixels += objects.pixelsWritten;
    }
}
