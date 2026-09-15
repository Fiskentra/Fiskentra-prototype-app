package android.os;
public class StatFs {
    public static long freeBytes=4L*1024*1024*1024;
    public StatFs(String path){}
    public long getAvailableBytes(){return freeBytes;}
}
