# Deployment guide

Swapp ships as one Docker image (see `Dockerfile`) that needs one PostgreSQL database (version 14 or newer). Images are stored in PostgreSQL by default (`STORAGE_TYPE=database`), so **no separate volume or bucket is needed**: uploaded photos survive redeploys automatically. Photos are resized in the browser before upload, so even a small free database holds thousands of them.

Required production environment variables:

```
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://<host>:<port>/<database>?sslmode=require
DB_USERNAME=<user>
DB_PASSWORD=<password>
JWT_SECRET=<at least 32 random bytes, e.g. `openssl rand -base64 48`>
CORS_ALLOWED_ORIGINS=https://<your-public-domain>   # optional on Render (defaults to RENDER_EXTERNAL_URL)
```

`COOKIE_SECURE` defaults to `true` in the `prod` profile, so the site must be served over HTTPS (all the options below provide it). Health check path: `/actuator/health`. The first start runs the Flyway migrations, which also enable the `pg_trgm` extension. All the providers below allow it.

---

## Option 1: Render (app) with Neon (database), both free with no expiry

### 1. Database: Neon

1. Sign up at <https://neon.tech> and create a project (PostgreSQL 17, region **AWS Asia Pacific (Singapore)**, which matches `region: singapore` in `render.yaml`; if you pick another region, change both so the app and database stay close together). The free plan does not expire. It includes 0.5 GB of storage, and compute sleeps after a few minutes idle and wakes in about a second.
2. On the project dashboard, open **Connect**, switch **Connection pooling off** (Flyway's migration lock needs a direct connection, and the app's own Hikari pool is enough), then copy the connection details:
   - **Host:** something like `ep-cool-name-123456.ap-southeast-1.aws.neon.tech`
   - **Database:** `neondb`
   - **Role / user:** `neondb_owner`
   - **Password:** shown in the connection string
3. Build the JDBC URL. It needs no port, because 5432 is the default:
   ```
   jdbc:postgresql://<host>/neondb?sslmode=require
   ```

### 2. App: Render

1. Sign up at <https://dashboard.render.com> with GitHub.
2. Choose **New → Blueprint** and select this repository. Render reads `render.yaml`, creates a free Docker web service and **generates `JWT_SECRET` itself**.
3. When prompted, enter `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` from Neon.
4. Click **Apply**. The first build takes 5-8 minutes. When the logs show `Started SwappApplication`, the site is live at `https://swapp-xxxx.onrender.com`.

Free Render web services sleep after 15 minutes without traffic, and the next visit takes about a minute to wake. Data is unaffected because it lives in Neon.

## Option 2: Railway (always on, about $5/month)

1. Create a project at <https://railway.com>, choose **Deploy from GitHub repo** and select this repository. The `Dockerfile` is detected.
2. Click **+ New → Database → PostgreSQL**.
3. On the Swapp service, add these variables:
   ```
   SPRING_PROFILES_ACTIVE=prod
   DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DB_USERNAME=${{Postgres.PGUSER}}
   DB_PASSWORD=${{Postgres.PGPASSWORD}}
   JWT_SECRET=<generated secret>
   CORS_ALLOWED_ORIGINS=https://<service>.up.railway.app
   ```
4. Under **Settings → Networking → Generate Domain**, create the public domain, and set the health check path to `/actuator/health`.

## Option 3: AWS EC2 with Amazon RDS for PostgreSQL

1. **RDS:** create a PostgreSQL 17 instance (db.t4g.micro), not publicly accessible. Allow port 5432 only from the EC2 security group, and create a `swapp` database.
2. **EC2:** launch Amazon Linux 2023, open ports 80 and 443, and install Docker (`sudo dnf install -y docker && sudo systemctl enable --now docker`).
3. Run the container:
   ```bash
   docker build -t swapp .
   docker run -d --name swapp --restart unless-stopped -p 127.0.0.1:8080:8080 --env-file /etc/swapp.env swapp
   ```
4. **HTTPS:** put Caddy in front of the app; it handles Let's Encrypt automatically:
   ```
   swapp.example.com {
       reverse_proxy 127.0.0.1:8080
   }
   ```

## After deploying

- `GET https://<domain>/actuator/health` should return `{"status":"UP"}`.
- Load demo data (optional): `cd e2e && npm install && node seed.mjs https://<domain>`
- Run the browser journey against the live site: `node journey.mjs https://<domain> ./shots`
- Check that the `swapp_token` cookie is `Secure; HttpOnly; SameSite=Strict`.

"Near me" uses the browser Geolocation API, which only works on HTTPS pages (and on `localhost`). Every option above serves HTTPS.
