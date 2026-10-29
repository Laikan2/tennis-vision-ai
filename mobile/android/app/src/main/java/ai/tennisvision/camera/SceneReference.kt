package ai.tennisvision.camera

import kotlin.math.*

/** Keep training alive through an obstruction; only resume scoring in the same view. */
internal class SceneReference {
  private var reference=IntArray(0);private var stableSince=0.0
  var blocked=false;private set
  var needsConfirmation=false;private set
  // Keep candidate contacts separate from calibration-dependent IN/OUT.
  var contactsBlocked=false;private set
  fun reset(){reference=IntArray(0);blocked=false;needsConfirmation=false;contactsBlocked=false;stableSince=0.0}
  fun update(pixels:IntArray,width:Int,height:Int,time:Double,changed:Boolean,compensated:Boolean=false){
    val sample=IntArray(240*3)
    for(y in 0 until 12)for(x in 0 until 20){
      val px=min(width-1,(2*x+1)*width/40);val py=min(height-1,(2*y+1)*height/24);val c=pixels[py*width+px];val i=(y*20+x)*3
      sample[i]=(c shr 16)and 255;sample[i+1]=(c shr 8)and 255;sample[i+2]=c and 255
    }
    if(reference.isEmpty()){reference=sample;return}
    if(changed){blocked=true;contactsBlocked=true;stableSince=time}
    if(!blocked){contactsBlocked=false;if(compensated)reference=sample;return}
    contactsBlocked=time-stableSince<.65
    if(time-stableSince<.25)return
    val shifts=IntArray(240){j->val i=j*3;(sample[i]+2*sample[i+1]+sample[i+2]-reference[i]-2*reference[i+1]-reference[i+2])/4}.sorted()
    val shift=shifts[120];val counts=IntArray(4)
    for(y in 0 until 12)for(x in 0 until 20){val i=(y*20+x)*3;val q=(if(x>=10)1 else 0)+(if(y>=6)2 else 0)
      if((0..2).all{abs(sample[i+it]-reference[i+it]-shift)<=24})counts[q]++
    }
    if(counts.sum()>=168 && counts.count{it>=36}>=3){blocked=false;needsConfirmation=false;contactsBlocked=false;reference=sample}
    else needsConfirmation=needsConfirmation || time-stableSince>=.65
  }
}
