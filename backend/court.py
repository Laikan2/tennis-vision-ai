class CourtDetector:
    """Calibrated boundary geometry. Automatic line recognition is a future adapter."""
    width = 10.97
    length = 23.77

    def classify(self, point, uncertainty_m=None, ground_confirmed=False):
        if point is None or uncertainty_m is None or not ground_confirmed:
            return 'INCONCLUSIVO'
        x,y = point
        signed_margin = min(x,self.width-x,y,self.length-y)
        # Conservative line/ball footprint guard. Court mode is doubles footprint only.
        guard = uncertainty_m+.04
        if abs(signed_margin)<=guard:
            return 'INCONCLUSIVO'
        return 'IN' if signed_margin>guard else 'OUT'
