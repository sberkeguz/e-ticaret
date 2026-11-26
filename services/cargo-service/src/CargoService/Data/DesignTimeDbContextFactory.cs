using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace CargoService.Data;

/// <summary>
/// Yalnızca <c>dotnet ef migrations add</c> gibi tasarım zamanı komutları için. Migration üretmek
/// veritabanına bağlanmayı gerektirmez; buradaki bağlantı dizesi hiçbir zaman uygulama tarafından
/// kullanılmaz (uygulama ConnectionStrings:Cargo olmadan açılmaz).
/// </summary>
public sealed class DesignTimeDbContextFactory : IDesignTimeDbContextFactory<CargoDbContext>
{
    public CargoDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<CargoDbContext>();
        CargoDbContext.Configure(options, "Host=localhost;Database=cargo_design_time");
        return new CargoDbContext(options.Options);
    }
}
