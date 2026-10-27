package ai.tennisvision.camera

enum class ContactEvidence { CONFIRMED_FLOOR, CONFIRMED_WALL, CANDIDATE, REJECTED }
val LocalImpact.evidence: ContactEvidence get()=when {
  decision==LineCall.REJECTED -> ContactEvidence.REJECTED
  decision==LineCall.IN || decision==LineCall.OUT ->
    if(surface==ContactSurface.WALL) ContactEvidence.CONFIRMED_WALL else ContactEvidence.CONFIRMED_FLOOR
  else -> ContactEvidence.CANDIDATE
}

data class TrackingDiagnostics(val trackId:Long?=null,val state:String="BUSCANDO",
  val candidates:Int=0,val usable:Int=0,val textureRejected:Int=0,
  val contactReason:String="Aguardando trajetória",val samples:List<BallObservation> = emptyList()) {
  val summary get()="ID ${trackId ?: "—"} · $state · $usable/$candidates alvos · $textureRejected texturas descartadas\n$contactReason"
}
