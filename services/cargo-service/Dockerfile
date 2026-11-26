FROM mcr.microsoft.com/dotnet/sdk:10.0-alpine AS build
WORKDIR /src

# Önce yalnızca proje/sürüm/kilit dosyaları: kaynak değişince restore katmanı önbellekten gelir.
# --locked-mode: packages.lock.json dışındaki bir sürüm çözülürse derleme durur.
COPY global.json Directory.Build.props Directory.Packages.props ./
COPY src/CargoService/CargoService.csproj src/CargoService/packages.lock.json src/CargoService/
RUN dotnet restore src/CargoService/CargoService.csproj --locked-mode

COPY src ./src
RUN dotnet publish src/CargoService/CargoService.csproj -c Release --no-restore -o /app -p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0-alpine
WORKDIR /app
ENV ASPNETCORE_HTTP_PORTS=8082 \
    DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1

COPY --from=build /app ./

EXPOSE 8082
USER app

# Açılışta bekleyen EF Core migration'ları uygulanır, sonra servis dinlemeye başlar.
# ConnectionStrings__Cargo verilmezse servis açılmaz.
ENTRYPOINT ["dotnet", "CargoService.dll"]
