using CargoService.Configuration;
using Microsoft.EntityFrameworkCore;

namespace CargoService.Data;

/// <summary>
/// Açılışta bekleyen migration'ları uygular (Database:MigrateOnStartup=true ise). Ayar doğrulamasından
/// sonra, HTTP sunucusu dinlemeye başlamadan önce çalışır; migration başarısızsa servis açılmaz.
/// </summary>
public sealed class MigrationHostedService(
    IServiceScopeFactory scopes,
    ILogger<MigrationHostedService> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopes.CreateAsyncScope();
        if (!CargoOptions.From(scope.ServiceProvider).Database.MigrateOnStartup)
        {
            logger.LogInformation("Açılış migration'ı kapalı (Database:MigrateOnStartup=false)");
            return;
        }

        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        await db.Database.MigrateAsync(cancellationToken);
        logger.LogInformation("Veritabanı migration'ları uygulandı");
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
