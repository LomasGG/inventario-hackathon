# Servicio de inventario del taller

## Levantar todo

1. El kit del reto (broker + simulador), desde su carpeta:

       docker compose up -d

2. Este servicio + su base, desde la raiz de este repo:

       docker compose --profile app up -d --build

Si la carpeta del kit no se llama `kit`, antes del paso 2 indica el nombre de su red
(`docker network ls`, la que termina en `_default`):

- Mac / Linux: `export KIT_NETWORK=<carpeta-del-kit>_default`
- Windows PowerShell: `$env:KIT_NETWORK = "<carpeta-del-kit>_default"`

API: http://localhost:8000

## Pruebas de contrato

Desde la carpeta del kit:

    docker compose run --rm contract-tests --api http://host.docker.internal:8000

## Reconstruccion desde cero

    docker compose --profile app down -v
    docker compose --profile app up -d --build

El servicio detecta la base vacia y relee los topicos desde el inicio.

## Trabajar sin Docker (desarrollo)

    docker compose up -d                       # solo Postgres
    cd backend && ./mvnw spring-boot:run       # el servicio

Para soltar el puerto 8000 del contenedor: `docker compose --profile app stop inventario`.