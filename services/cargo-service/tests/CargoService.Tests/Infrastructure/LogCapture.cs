using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;

namespace CargoService.Tests.Infrastructure;

/// <summary>
/// Uygulamanın yazdığı bütün log satırlarını (Trace dahil, her kategori) bellekte toplar: biçimlenmiş
/// mesaj, yapısal alanlar, kapsamlar ve istisnanın tamamı (ToString) tek metin olarak saklanır.
/// </summary>
public sealed class LogCapture : ILoggerProvider, ISupportExternalScope
{
    private readonly ConcurrentQueue<string> _lines = new();
    private IExternalScopeProvider _scopes = new LoggerExternalScopeProvider();

    public IReadOnlyCollection<string> Lines => _lines.ToArray();

    public ILogger CreateLogger(string categoryName) => new Logger(this, categoryName);

    public void SetScopeProvider(IExternalScopeProvider scopeProvider) => _scopes = scopeProvider;

    public void Dispose()
    {
    }

    private sealed class Logger(LogCapture owner, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => owner._scopes.Push(state);

        public bool IsEnabled(LogLevel logLevel) => true;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            var line = new System.Text.StringBuilder($"{logLevel} {category}: {formatter(state, exception)}");
            if (state is IEnumerable<KeyValuePair<string, object?>> fields)
            {
                foreach (var (key, value) in fields)
                {
                    line.Append($" | {key}={value}");
                }
            }
            owner._scopes.ForEachScope((scope, builder) => builder.Append($" | scope={scope}"), line);
            if (exception is not null)
            {
                line.Append($" | {exception}");
            }
            owner._lines.Enqueue(line.ToString());
        }
    }
}
