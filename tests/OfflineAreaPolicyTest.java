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
        double[] polar=OfflineAreaPolicy.bounds(84.99,179.99,10);
        check(polar[0]<=85&&polar[1]<=180,"Provider latitude and longitude limits are clamped");
        boolean rejected=false;try{OfflineAreaPolicy.bounds(91,0,5);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Invalid centre rejected");
        check(OfflineAreaPolicy.MIN_ZOOM<OfflineAreaPolicy.MAX_ZOOM,"Zoom range is ordered");
        System.out.println("OfflineAreaPolicy: "+checks+" checks passed");
    }
}
