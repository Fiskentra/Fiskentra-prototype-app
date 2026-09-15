import com.fiskentra.app.search.*;
import java.util.*;
import java.util.concurrent.*;

public class PlaceSearchTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static final String VALID = "{\"type\":\"FeatureCollection\",\"features\":[{\"id\":\"lake.1\",\"text\":\"Озеро & lake\",\"place_name\":\"Озеро & lake, Latvia\",\"place_type\":[\"water\"],\"geometry\":{\"type\":\"Point\",\"coordinates\":[24.2,56.8]},\"bbox\":[24,56,25,57]}]}";
    private static void malformed(String value) throws Exception { try { MapTilerGeocoding.parse(value,1); throw new AssertionError("accepted malformed"); } catch(PlaceSearchProvider.Failure failure) { check(failure.category == PlaceSearchProvider.Error.MALFORMED,"malformed categorized"); } }
    private static PlaceSearchProvider.Request query(String text) { return new PlaceSearchProvider.Request(text,Double.NaN,Double.NaN,null); }
    public static void main(String[] args) throws Exception {
        PlaceResult value = MapTilerGeocoding.parse(VALID,123).get(0);
        check(value.latitude == 56.8 && value.longitude == 24.2,"GeoJSON longitude/latitude normalized");
        check(value.name.equals("Озеро & lake") && value.context.equals("Latvia"),"Unicode name/context preserved");
        check(value.areaDestination() && value.bounds()[0] == 24,"area center requires destination choice");
        check(!value.cached && value.fetchedAtUtc == 123,"live result metadata");
        check(value.attribution.contains("MapTiler") && value.attribution.contains("OpenStreetMap"),"attribution fallback");
        double[] box = value.bounds(); box[0] = 0; check(value.bounds()[0] == 24,"bounds immutable");
        check(MapTilerGeocoding.parse("{\"type\":\"FeatureCollection\",\"features\":[]}",1).isEmpty(),"empty collection is empty not malformed");
        malformed("{}"); malformed("not json"); malformed(VALID.replace("[24.2,56.8]","[56.8,124.2]"));
        malformed(VALID.replace("\"geometry\":{\"type\":\"Point\",\"coordinates\":[24.2,56.8]}","\"geometry\":null"));
        malformed(VALID.replace("\"Point\"","\"Polygon\""));
        String invalidBounds = VALID.replace("[24,56,25,57]","[25,56,24,57]"); check(MapTilerGeocoding.parse(invalidBounds,1).get(0).bounds() == null,"invalid bounds discarded");
        MapTilerGeocoding provider = new MapTilerGeocoding("test-placeholder");
        String url = provider.requestUrl(new PlaceSearchProvider.Request("Камыш & /?#",56,24,null));
        check(url.contains("%D0%9A") && url.contains("%26") && url.contains("%2F%3F%23"),"Unicode/path/query characters encoded");
        check(url.contains("proximity=24.0,56.0") && !url.contains("bbox=") && !url.contains("country="),"only map-center bias and no hidden country restriction");
        check(provider.requestUrl(new PlaceSearchProvider.Request("Lake",56,24,new double[]{20,50,30,60})).contains("bbox=20.0,50.0,30.0,60.0"),"explicit area bounds");
        try { new MapTilerGeocoding("").requestUrl(query("lake")); throw new AssertionError("missing key accepted"); } catch(PlaceSearchProvider.Failure failure) { check(failure.category == PlaceSearchProvider.Error.CONFIGURATION,"empty key config error"); }
        check(MapTilerGeocoding.httpFailure(403,null,0).category == PlaceSearchProvider.Error.CONFIGURATION,"403 key restriction");
        check(MapTilerGeocoding.httpFailure(429,"60",0).retryAfterMillis == 60000,"429 seconds retry-after");
        check(MapTilerGeocoding.retryMillis("Wed, 21 Oct 2015 07:28:00 GMT",1445412420000L) == 60000,"HTTP date retry-after");
        check(MapTilerGeocoding.retryMillis("broken",0) == 30000,"invalid retry-after bounded default");
        check(LocalPointSearch.matches("ЩУКА","Щука у камышей","catch","note"),"local Cyrillic case insensitive");
        check(LocalPointSearch.matches("REEDS",null,"waypoint","Near reeds") && LocalPointSearch.matches("",null,null,null),"local notes and null-safe query without network");
        PlaceSearchProvider.Cancellation cancelled = new PlaceSearchProvider.Cancellation(); cancelled.cancel();
        try { provider.search(query("lake"),cancelled); throw new AssertionError("cancel ignored"); } catch(PlaceSearchProvider.Failure failure) { check(failure.category == PlaceSearchProvider.Error.CANCELLED,"pre-cancelled search does no IO"); }
        QueueExecutor io = new QueueExecutor(); ManualTimer timer = new ManualTimer(); List<Runnable> deliver = new ArrayList<>(); List<String> requests = new ArrayList<>(); List<PlaceSearchController.State> states = new ArrayList<>();
        PlaceSearchController controller = new PlaceSearchController((request,cancel) -> { requests.add(request.query); return MapTilerGeocoding.parse(VALID,1); },deliver::add,timer,io,() -> timer.now);
        controller.search(query("a"),states::add); timer.advance(1000); io.runAll(); check(requests.isEmpty(),"minimum two characters");
        controller.search(query("la"),states::add); timer.advance(200); controller.search(query("lake"),states::add); timer.advance(299); io.runAll(); check(requests.isEmpty(),"debounce restarts");
        timer.advance(1); io.runAll(); check(requests.equals(Arrays.asList("lake")),"one debounced provider call");
        // Delivery deliberately arrives backwards, including an already completed obsolete response.
        controller.search(query("river"),states::add); timer.advance(300); io.runAll(); Collections.reverse(deliver); for(Runnable call:deliver)call.run(); deliver.clear();
        check(states.stream().filter(s -> s.status == PlaceSearchController.Status.RESULTS).count() == 1,"latest wins despite old delivery and ineffective network cancellation");
        states.clear(); controller.search(query("bay"),states::add); timer.advance(300); controller.close(); io.runAll(); for(Runnable call:deliver)call.run(); check(states.isEmpty(),"leaving screen cancels queued callbacks");
        QueueExecutor rateIo = new QueueExecutor(); ManualTimer rateTimer = new ManualTimer(); List<PlaceSearchController.State> rateStates = new ArrayList<>(); int[] attempts={0};
        PlaceSearchController rate = new PlaceSearchController((request,cancel) -> { attempts[0]++; throw new PlaceSearchProvider.Failure(PlaceSearchProvider.Error.RATE_LIMIT,5000); },Runnable::run,rateTimer,rateIo,()->rateTimer.now);
        rate.search(query("lake"),rateStates::add); rateTimer.advance(300); rateIo.runAll(); rate.search(query("river"),rateStates::add); rateTimer.advance(300); rateIo.runAll(); check(attempts[0]==1,"Retry-After prevents requests even for new query");
        rateTimer.advance(5000); rate.search(query("river"),rateStates::add); rateTimer.advance(300); rateIo.runAll(); check(attempts[0]==2,"retry available after provider cooldown"); rate.close();
        QueueExecutor slowIo = new QueueExecutor(); ManualTimer slowTimer = new ManualTimer(); List<PlaceSearchController.State> slowStates = new ArrayList<>();
        PlaceSearchController slow = new PlaceSearchController((request,cancel)->Collections.emptyList(),Runnable::run,slowTimer,slowIo,()->slowTimer.now);
        slow.search(query("lake"),slowStates::add); slowTimer.advance(300); slowTimer.advance(10000);
        check(slowStates.get(slowStates.size()-1).failure.category==PlaceSearchProvider.Error.TIMEOUT,"whole operation timeout independent of transport"); slowIo.runAll(); check(slowStates.get(slowStates.size()-1).status==PlaceSearchController.Status.ERROR,"late success cannot replace timeout"); slow.close();
        for(PlaceSearchProvider.Error error:Arrays.asList(PlaceSearchProvider.Error.OFFLINE,PlaceSearchProvider.Error.TIMEOUT,PlaceSearchProvider.Error.MALFORMED,PlaceSearchProvider.Error.CONFIGURATION)) {
            QueueExecutor errIo=new QueueExecutor(); ManualTimer errTimer=new ManualTimer(); List<PlaceSearchController.State> errStates=new ArrayList<>();
            PlaceSearchController errors=new PlaceSearchController((request,cancel)->{throw new PlaceSearchProvider.Failure(error);},Runnable::run,errTimer,errIo,()->errTimer.now);
            errors.search(query("lake"),errStates::add);errTimer.advance(300);errIo.runAll();check(errStates.get(errStates.size()-1).failure.category==error,"error propagated: "+error);errors.close();
        }
        System.out.println("PASS "+checks+" place-search checks");
    }
    private static class QueueExecutor extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>(); boolean stopped;
        public void execute(Runnable runnable){tasks.add(runnable);} void runAll(){List<Runnable> copy=new ArrayList<>(tasks);tasks.clear();for(Runnable task:copy)task.run();}
        public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;return new ArrayList<>(tasks);}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped;}public boolean awaitTermination(long timeout,TimeUnit unit){return stopped;}
    }
    private static final class ManualTimer extends QueueExecutor implements ScheduledExecutorService {
        long now; final List<Task<?>> scheduled=new ArrayList<>();
        void advance(long millis){now+=millis; boolean again;do{again=false;for(Task<?> task:new ArrayList<>(scheduled))if(task.when<=now&&!task.isDone()){task.run();again=true;}}while(again);}
        public ScheduledFuture<?> schedule(Runnable r,long delay,TimeUnit unit){return schedule(Executors.callable(r,null),delay,unit);}
        public <V> ScheduledFuture<V> schedule(Callable<V> c,long delay,TimeUnit unit){Task<V> t=new Task<>(c,now+unit.toMillis(delay));scheduled.add(t);return t;}
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}
        final class Task<V> extends FutureTask<V> implements ScheduledFuture<V>{final long when;Task(Callable<V>c,long when){super(c);this.when=when;}public long getDelay(TimeUnit u){return u.convert(when-now,TimeUnit.MILLISECONDS);}public int compareTo(Delayed other){return Long.compare(getDelay(TimeUnit.MILLISECONDS),other.getDelay(TimeUnit.MILLISECONDS));}}
    }
}
