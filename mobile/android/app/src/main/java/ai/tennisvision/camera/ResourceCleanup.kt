package ai.tennisvision.camera

/** Release every resource even if the camera or network fails to close. */
internal fun closeResources(vararg operations: () -> Unit) {
  var first: Exception? = null
  operations.forEach { close ->
    try { close() }
    catch (error: Exception) {
      if (first == null) first = error else if (first !== error) first!!.addSuppressed(error)
    }
  }
  first?.let { throw it }
}
