namespace CargoService.Tests.Infrastructure;

/// <summary>
/// Kapsayıcı gerektiren testler bu koleksiyonda toplanır: kapsayıcılar bir kez açılır,
/// testler sırayla çalışır (aynı veritabanını paylaştıkları için).
/// </summary>
[CollectionDefinition("Integration")]
public sealed class IntegrationCollection : ICollectionFixture<ContainersFixture>;
