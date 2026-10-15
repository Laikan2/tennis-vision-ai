package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class SceneReferenceTest {
  private val w=320;private val h=240
  private val room=IntArray(w*h){i->val x=i%w;val y=i/w;val v=60+(x/40+y/30)%3*45;0xff000000.toInt() or(v shl 16)or(v shl 8)or v}
  @Test fun obstructionRecoversOnlyAfterOriginalRoomReturns() {
    val g=SceneReference();g.update(room,w,h,0.0,false)
    val hand=IntArray(w*h){0xffbc8062.toInt()}
    g.update(hand,w,h,.1,true);assertTrue(g.blocked)
    g.update(hand,w,h,1.0,false);assertTrue(g.blocked);assertTrue(g.needsConfirmation)
    g.update(room,w,h,1.1,false);assertFalse(g.blocked);assertFalse(g.needsConfirmation)
  }
  @Test fun aDifferentViewCannotResumeInOutJustBecauseItStoppedMoving() {
    val g=SceneReference();g.update(room,w,h,0.0,false)
    val changed=IntArray(w*h){i->room[room.lastIndex-i]}
    g.update(changed,w,h,.1,true)
    g.update(changed,w,h,1.0,false);assertTrue(g.blocked);assertTrue(g.needsConfirmation)
    g.reset();g.update(changed,w,h,2.0,false);assertFalse(g.blocked)
  }
  @Test fun personInOnePartOfTheRoomDoesNotPreventRecovery() {
    val g=SceneReference();g.update(room,w,h,0.0,false)
    g.update(IntArray(w*h){0xffbc8062.toInt()},w,h,.1,true)
    val restored=room.copyOf();for(y in 60..140)for(x in 120..200)restored[y*w+x]=0xffbc8062.toInt()
    g.update(restored,w,h,.5,false);assertFalse(g.blocked)
  }
  @Test fun impactsRemainSuppressedUntilTheSceneIsRecovered() {
    val core=LocalAnalysis(LocalSettings(court=CourtLayout.FULL,polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.9),CourtPoint(.1,.9))))
    core.process(room,w,h,0.0)
    val blocked=core.process(IntArray(w*h){0xffbc8062.toInt()},w,h,.1)
    assertTrue(blocked.movingCamera);assertNull(blocked.impact)
    val returned=core.process(room,w,h,1.0)
    assertNull(returned.impact)
    val stable=core.process(room,w,h,1.5)
    assertFalse(stable.movingCamera);assertFalse(stable.areaNeedsConfirmation)
  }
}
