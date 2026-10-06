import numpy as np
from .target import court_sector


class StatisticsEngine:
    @staticmethod
    def summarize(events, samples=None):
        confirmed = [e for e in events if e.get('status')=='confirmed']
        speeds = [e['physicalKmh'] for e in (samples or []) if e.get('physicalKmh') is not None and
                  np.isfinite(e['physicalKmh']) and 0 <= e['physicalKmh'] <= 250]
        estimates = [e['speedBefore'] for e in events if e.get('speedBefore') is not None and
                     np.isfinite(e['speedBefore']) and 0 <= e['speedBefore'] <= 250]
        # IN/OUT here is the decision relative to the user-marked target. It is
        # useful for the live VAR even while an impact remains a candidate for
        # human review, so do not hide it behind physical-contact confirmation.
        inside=sum(e.get('inOrOut')=='IN' for e in events)
        outside=sum(e.get('inOrOut')=='OUT' for e in events)
        inconclusive=sum(e.get('inOrOut') not in ('IN','OUT') for e in events)
        cameras={camera_id for event in events for camera_id in
                 (event.get('cameraIds') or ([event.get('cameraId')] if event.get('cameraId') else []))}
        confidences=[float(e['confidence']) for e in events if e.get('confidence') is not None and
                     np.isfinite(e['confidence']) and 0 <= e['confidence'] <= 1]
        stroke_stats={}
        for name in ('forehand','backhand','unclassified'):
            rows=[event for event in events if
                  (event.get('strokeType') if event.get('strokeType') in ('forehand','backhand')
                   else 'unclassified') == name]
            row_speeds=[float(event['speedBefore']) for event in rows
                        if event.get('speedBefore') is not None and
                        np.isfinite(event['speedBefore']) and 0 <= event['speedBefore'] <= 250]
            row_inside=sum(event.get('inOrOut') == 'IN' for event in rows)
            row_outside=sum(event.get('inOrOut') == 'OUT' for event in rows)
            stroke_stats[name]=dict(total=len(rows),inside=row_inside,outside=row_outside,
                                    insideRate=(row_inside/(row_inside+row_outside)*100
                                                if row_inside+row_outside else None),
                                    maxKmh=max(row_speeds) if row_speeds else None,
                                    meanKmh=float(np.mean(row_speeds)) if row_speeds else None)
        sectors={}
        for event in events:
            if event.get('analysisMode') == 'room':
                continue
            sector=event.get('courtSector') or court_sector(event.get('targetPosition'))
            row=sectors.setdefault(sector,dict(total=0,forehand=0,backhand=0,
                                               unclassified=0,inside=0,outside=0))
            stroke=(event.get('strokeType') if event.get('strokeType') in ('forehand','backhand')
                    else 'unclassified')
            row['total']+=1;row[stroke]+=1
            if event.get('inOrOut') == 'IN':row['inside']+=1
            if event.get('inOrOut') == 'OUT':row['outside']+=1
        streak=longest=0
        for event in sorted(events, key=lambda item: item.get('timestamp',0)):
            streak=streak+1 if event.get('inOrOut') == 'IN' else 0
            longest=max(streak,longest)
        return dict(bounces=len(confirmed), candidates=sum(e.get('status')=='candidate' for e in events),
                    throws=len(events), impacts=len(events),
                    locatedImpacts=sum(e.get('contactLocated',True) is True for e in events),
                    reviewedImpacts=len(confirmed), longestInsideStreak=longest,
                    decidedImpacts=inside+outside,
                    estimatedMedianKmh=float(np.median(estimates)) if estimates else None,
                    estimatedP90Kmh=float(np.percentile(estimates,90)) if estimates else None,
                    estimatedMaxKmh=max(estimates) if estimates else None,
                    estimatedMeanKmh=float(np.mean(estimates)) if estimates else None,
                    inside=inside, outside=outside, inconclusive=inconclusive,
                    insideRate=inside/(inside+outside)*100 if inside+outside else None,
                    accuracy=inside/(inside+outside)*100 if inside+outside else None,
                    varAlerts=outside, camerasUsed=len(cameras),
                    multiCameraEvents=sum(bool(e.get('multiCamera')) for e in events),
                    strokeStats=stroke_stats,courtSectors=sectors,
                    averageDetectionConfidence=float(np.mean(confidences))*100 if confidences else None,
                    maxKmh=max(speeds) if speeds else None, meanKmh=float(np.mean(speeds)) if speeds else None,
                    minKmh=min(speeds) if speeds else None, medianKmh=float(np.median(speeds)) if speeds else None,
                    p90Kmh=float(np.percentile(speeds,90)) if speeds else None,
                    rallies=None, longestRally=None)

    @staticmethod
    def validation(rows):
        if not rows:
            return dict(count=0,mae=None,bias=None,std=None,ci95=None,mape=None)
        error=np.array([r['estimated']-r['reference'] for r in rows])
        n=len(error)
        std=float(np.std(error,ddof=1)) if n>1 else None
        # Normal-approximation interval only with enough independent observations.
        ci=[float(np.mean(error)-1.96*std/np.sqrt(n)),float(np.mean(error)+1.96*std/np.sqrt(n))] if n>=30 else None
        return dict(count=n,mae=float(np.abs(error).mean()),bias=float(error.mean()),std=std,ci95=ci,
                    mape=float(np.mean([abs(r['estimated']-r['reference'])/r['reference']*100 for r in rows])),
                    ciMethod='normal approximation for mean bias; independent pairs required' if ci else None)
