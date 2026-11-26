using CargoService.Configuration;
using Microsoft.Extensions.Options;

namespace CargoService.Carriers.Fake;

/// <summary>
/// Sahte firmanın bellekteki hata planı: hangi hata (<see cref="FakeFailMode"/>) kaç gönderi açma
/// çağrısına daha uygulanacak. Açılışta <c>Carrier:Fake:FailMode</c>/<c>FailTimes</c>'tan okunur,
/// <c>POST /test/fake-carrier/faults</c> ile değiştirilir. Sayaç bitince mod None'a döner.
/// Süreç başınadır (servis yeniden başlayınca ayardaki plana döner).
/// </summary>
public sealed class FakeCarrierFaults
{
    private readonly Lock _lock = new();
    private FakeFailMode _mode;
    private int _remaining;

    public FakeCarrierFaults(IOptions<CargoOptions> options)
    {
        var fake = options.Value.Carrier.Fake;
        Set(fake.FailMode, fake.FailTimes);
    }

    /// <summary>Planı değiştirir; <paramref name="times"/> &lt;= 0 ya da mod None ise hata uygulanmaz.</summary>
    public void Set(FakeFailMode mode, int times)
    {
        lock (_lock)
        {
            (_mode, _remaining) = mode == FakeFailMode.None || times <= 0 ? (FakeFailMode.None, 0) : (mode, times);
        }
    }

    /// <summary>Bu çağrıya uygulanacak hatayı döner ve sayacı bir azaltır; plan yoksa None.</summary>
    public FakeFailMode TryConsume()
    {
        lock (_lock)
        {
            if (_remaining <= 0)
            {
                return FakeFailMode.None;
            }
            var mode = _mode;
            if (--_remaining == 0)
            {
                _mode = FakeFailMode.None;
            }
            return mode;
        }
    }
}
