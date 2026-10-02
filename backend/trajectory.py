def speed_color(kmh, thresholds=(0,40,70,100,130)):
    colors = [(60,150,255),(70,220,155),(235,225,75),(255,148,55),(248,80,85)]
    if kmh is None:
        return '#a4b6c1'
    if kmh>=thresholds[-1]:
        return '#f85055'
    for i in range(len(thresholds)-1):
        if kmh<=thresholds[i+1]:
            t=max(0,(kmh-thresholds[i])/(thresholds[i+1]-thresholds[i]))
            rgb=[round(a+(b-a)*t) for a,b in zip(colors[i],colors[i+1])]
            return '#'+''.join(f'{v:02x}' for v in rgb)
    return '#a4b6c1'


class TrajectoryEstimator:
    def points(self, history, length=50):
        return [dict(x=p['centerX'],y=p['centerY'],predicted=p['predicted'],timestamp=p['timestamp']) for p in list(history)[-length:]]
