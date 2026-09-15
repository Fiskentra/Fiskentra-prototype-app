import com.fiskentra.app.model.OfflineAreaPolicy;

public final class OfflineAreaPolicyTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        check(OfflineAreaPolicy.normalizeRadius(1)==2,"Small radius becomes 2 km");
        check(OfflineAreaPolicy.normalizeRadius(4)==5,"Middle radius becomes 5 km");
        check(OfflineAreaPolicy.normalizeRadius(9)==10,"Large radius becomes 10 km");
        double[] area=OfflineAreaPolicy.bounds(60,17,5);
        check(area[0]>60&&area[2]<60,"Latitude surrounds centre");
        check(area[1]>17&&area[3]<17,"Longitude surrounds centre");
        check(Math.abs((area[0]-60)-(60-area[2]))<.000001,"Latitude bounds are symmetric");
        rejected(85.05,0,10,"A polar area must not silently lose its radius");
        rejected(0,179.99,5,"Date-line crossing is rejected before SDK download");
        rejected(0,-179.99,5,"West date-line crossing is rejected too");
        rejected(-85.05,0,10,"Southern polar edge is rejected");
        double[] polar=OfflineAreaPolicy.bounds(84.9,0,2);
        check(polar[1]>0.19,"Longitude uses true high-latitude scale without cosine clamping");
        boolean rejected=false;try{OfflineAreaPolicy.bounds(91,0,5);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Invalid centre rejected");
        check(OfflineAreaPolicy.MIN_ZOOM<OfflineAreaPolicy.MAX_ZOOM,"Zoom range is ordered");
        rejected(Double.NaN,0,5,"NaN is rejected");
        rejected(0,Double.POSITIVE_INFINITY,5,"Infinity is rejected");
        rejected(0,0,Double.NaN,"Invalid radius is rejected");
        check(OfflineAreaPolicy.contains(area,60,17),"The chosen center is in bounds");
        check(!OfflineAreaPolicy.contains(area,61,17),"Distant location is outside");
        check(OfflineAreaPolicy.contains(area,area[0],area[1]),"Exact boundaries are included");
        check(OfflineAreaPolicy.coverage(true,area,"hybrid-v4","hybrid-v4",8,16,60,17,12)==OfflineAreaPolicy.Coverage.AVAILABLE,"Only complete matching style/area/zoom is covered");
        check(OfflineAreaPolicy.coverage(false,area,"hybrid-v4","hybrid-v4",8,16,60,17,12)==OfflineAreaPolicy.Coverage.INCOMPLETE,"Partial cache never represents ready coverage");
        check(OfflineAreaPolicy.coverage(true,area,"hybrid-v4","outdoor-v4",8,16,60,17,12)==OfflineAreaPolicy.Coverage.DIFFERENT_STYLE,"Satellite and Outdoor are different packs");
        check(OfflineAreaPolicy.coverage(true,area,"hybrid-v4","hybrid-v4",8,16,61,17,12)==OfflineAreaPolicy.Coverage.OUTSIDE_BOUNDS,"Outside bounds cannot advertise availability");
        check(OfflineAreaPolicy.coverage(true,area,"hybrid-v4","hybrid-v4",8,16,60,17,17)==OfflineAreaPolicy.Coverage.OUTSIDE_ZOOM,"Zoom 17 is not part of a zoom 8-16 pack");
        check(OfflineAreaPolicy.coverage(true,area,"hybrid-v4","hybrid-v4",8,16,60,17,7)==OfflineAreaPolicy.Coverage.OUTSIDE_ZOOM,"Below minimum zoom is also outside");
        check(OfflineAreaPolicy.progress(false,false,100,100)==-1,"Discovering resource count remains indeterminate");
        check(OfflineAreaPolicy.progress(false,true,100,100)==99,"100 percent requires SDK completion");
        check(OfflineAreaPolicy.progress(true,false,0,0)==100,"SDK completion is authoritative");
        check(OfflineAreaPolicy.progress(false,true,40,100)==40,"Precise progress displays the real proportion");
        check(OfflineAreaPolicy.progress(false,true,0,0)==-1,"Unknown total is not a percentage");
        check(!OfflineAreaPolicy.hasSpace(OfflineAreaPolicy.FREE_RESERVE_BYTES,1),"Download preserves free disk reserve");
        check(OfflineAreaPolicy.hasSpace(OfflineAreaPolicy.FREE_RESERVE_BYTES+1000,1000),"Planning estimate fits only beyond the reserve");
        check(OfflineAreaPolicy.approximateBytes(OfflineAreaPolicy.bounds(60,17,10))>OfflineAreaPolicy.approximateBytes(OfflineAreaPolicy.bounds(60,17,2)),"Larger radius has a larger planning size");
        System.out.println("OfflineAreaPolicy: "+checks+" checks passed");
    }
    private static void rejected(double lat,double lon,double radius,String message){boolean bad=false;try{OfflineAreaPolicy.bounds(lat,lon,radius);}catch(IllegalArgumentException expected){bad=true;}check(bad,message);}
}
