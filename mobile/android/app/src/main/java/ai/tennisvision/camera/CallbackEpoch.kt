package ai.tennisvision.camera

/** A stopped stream cannot deliver connection callbacks into its replacement. */
class CallbackEpoch {
  @Volatile private var value = 0L
  @Synchronized fun advance(): Long { value++; return value }
  fun current(): Long = value
  fun isCurrent(token: Long) = token == value
}
