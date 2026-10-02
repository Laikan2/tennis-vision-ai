import type { Person } from "../lib/types";

const limbs = [[5,6],[5,7],[7,9],[6,8],[8,10],[5,11],[6,12],[11,12],[11,13],[13,15],[12,14],[14,16],[0,1],[0,2],[1,3],[2,4]];
export function PoseOverlay({persons,width=1920}:{persons:Person[];width?:number}) {
  return <g aria-label="Esqueleto corporal detectado">
    {persons.map((person,index)=>{
      const joints=person.keypoints || [];
      return <g key={index} opacity={(person.poseAgeMs || 0) > 450 ? .5 : .95}>
        <rect x={person.x} y={person.y} width={person.width} height={person.height}
          fill="none" stroke="#66d2ff" strokeWidth={Math.max(2,width/760)}
          strokeDasharray={`${Math.max(8,width/180)} ${Math.max(5,width/280)}`} />
        <rect x={person.x} y={Math.max(0,person.y-Math.max(25,width/70))}
          width={Math.max(92,width/13)} height={Math.max(25,width/70)}
          fill="#102934" fillOpacity=".9" />
        <text x={person.x+Math.max(8,width/240)} y={Math.max(18,person.y-Math.max(7,width/250))}
          fill="#92e1ff" fontSize={Math.max(17,width/85)} fontWeight="700">PESSOA</text>
        {limbs.map(([a,b])=>{
          const p=joints[a],q=joints[b];
          return p?.[2]>=.35 && q?.[2]>=.35 ? <line key={`${a}-${b}`} x1={p[0]} y1={p[1]} x2={q[0]} y2={q[1]} stroke="#eef6f1" strokeWidth={Math.max(2,width/700)} strokeLinecap="round" /> : null;
        })}
        {joints.map(([x,y,confidence],i)=>confidence>=.35 ? <circle key={i} cx={x} cy={y} r={Math.max(3,width/440)} fill={i<5 ? "#f5f6ff" : "#baff56"} stroke="#192921" strokeWidth="1" /> : null)}
      </g>;
    })}
  </g>;
}
