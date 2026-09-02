package name.rhythm_axe_mod;

public class TickrateState {
    private static volatile float clientTickRate = 20.0f;

    public static float getClientTickRate() {
        return clientTickRate;
    }

    public static void setClientTickRate(float tickRate) {
        if (tickRate > 20.0f) {
            clientTickRate = tickRate;
        } else {
            clientTickRate = 20.0f;
        }
    }
}
