package pl.homemind.garettgps;
import android.location.Location;
import android.os.SystemClock;
import java.util.ArrayDeque;

final class Stabilizer {
    final ArrayDeque<Location> window=new ArrayDeque<>();
    Location best;
    final long start=SystemClock.elapsedRealtime();
    boolean accept(Location l) {
        long age=SystemClock.elapsedRealtime()-l.getElapsedRealtimeNanos()/1000000L;
        if(age<0 || age>5000 || l.getElapsedRealtimeNanos()/1000000L<start ||
           !l.hasAccuracy() || !Float.isFinite(l.getAccuracy()) || l.getAccuracy()<=0 ||
           !Double.isFinite(l.getLatitude()) || !Double.isFinite(l.getLongitude()) ||
           Math.abs(l.getLatitude())>90 || Math.abs(l.getLongitude())>180 || l.isFromMockProvider()) return false;
        Location prev=window.peekLast();
        if(prev!=null) {
            double dt=(l.getElapsedRealtimeNanos()-prev.getElapsedRealtimeNanos())/1e9;
            if(dt<0.7) return false;
            double speed=Math.max(l.hasSpeed()?l.getSpeed():0,prev.hasSpeed()?prev.getSpeed():0);
            double distance=l.distanceTo(prev);
            double limit=Math.max(15,prev.getAccuracy()+l.getAccuracy()+Math.max(3,speed+3)*dt);
            if(dt>10 || distance>limit) window.clear();
        }
        Location copy=new Location(l);
        window.add(copy); while(window.size()>7) window.removeFirst();
        if(best==null || l.getAccuracy()<=best.getAccuracy() ||
           l.getElapsedRealtimeNanos()-best.getElapsedRealtimeNanos()>15000000000L) best=copy;
        return true;
    }
    boolean stable(float target) {
        if(window.size()<5) return false;
        Location first=window.peekFirst(),last=window.peekLast();
        if(last.getElapsedRealtimeNanos()-first.getElapsedRealtimeNanos()<5000000000L) return false;
        for(Location l:window) if(l.getAccuracy()>target) return false;
        return true;
    }
    Location recentBest() {
        long now=SystemClock.elapsedRealtime();
        Location result=null;
        for(Location l:window) if(now-l.getElapsedRealtimeNanos()/1000000L<=15000 &&
             (result==null || l.getAccuracy()<=result.getAccuracy())) result=l;
        if(result==null && best!=null && now-best.getElapsedRealtimeNanos()/1000000L<=15000) result=best;
        // Return latest when moving rather than a stale high-accuracy sample.
        Location last=window.peekLast();
        if(result!=null && last!=null && now-last.getElapsedRealtimeNanos()/1000000L<=5000 &&
           ((last.hasSpeed() && last.getSpeed()>1.2f) || last.distanceTo(result)>Math.max(10,last.getAccuracy()))) return last;
        return result;
    }
}
