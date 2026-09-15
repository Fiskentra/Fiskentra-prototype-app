package android.content;
public class Context {
    public Context getApplicationContext(){return this;}
    public android.content.res.Resources getResources(){return new android.content.res.Resources();}
    public java.io.File getFilesDir(){return new java.io.File(".");}
    public String getString(int id){return "resource:"+id;}
}
