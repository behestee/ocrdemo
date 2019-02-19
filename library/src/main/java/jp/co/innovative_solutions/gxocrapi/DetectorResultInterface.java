package jp.co.innovative_solutions.gxocrapi;

public interface DetectorResultInterface {

    public void onMatchFound(String matchedItem);
    public void onMatchError(String ErrorMsg);
}
