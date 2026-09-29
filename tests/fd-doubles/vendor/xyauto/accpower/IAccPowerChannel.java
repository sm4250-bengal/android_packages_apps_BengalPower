package vendor.xyauto.accpower;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
/** Host call boundary only; never used by check-platform-api.sh. */
public interface IAccPowerChannel {
    int VERSION = 1;
    String HASH = "433d5bf62db24a55b9dac824f638ff2e21f11150";
    int getInterfaceVersion();
    String getInterfaceHash();
    void offerChannel(ParcelFileDescriptor endpoint) throws Exception;
    abstract class Stub {
        public static IAccPowerChannel asInterface(IBinder binder) { return (IAccPowerChannel) binder; }
    }
}
