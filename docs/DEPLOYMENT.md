# Deployment guide

Swapp ships as one Docker image (see `Dockerfile`) that needs one MySQL 8 database. Images are stored in MySQL by default (`STORAGE_TYPE=database`), so **no separate volume or bucket is needed**: uploaded photos survive redeploys automatically.

Required production environment variables:

```
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:mysql://<host>:<port>/<database>?connectionTimeZone=UTC&sslMode=REQUIRED
DB_USERNAME=<user>
DB_PASSWORD=<password>
JWT_SECRET=<at least 32 random bytes, e.g. `openssl rand -base64 48`>
CORS_ALLOWED_ORIGINS=https://<your-public-domain>
```

`COOKIE_SECURE` defaults to `true` in the `prod` profile, so the site must be served over HTTPS (all the options below provide it). Health check path: `/actuator/health`.

---

## Option 1: Railway (recommended: simplest, HTTPS included)

1. Create a project at <https://railway.com> and choose **Deploy from GitHub repo** → select this repository. Railway detects the `Dockerfile`.
2. In the same project click **+ New → Database → MySQL**.
3. Open the Swapp service → **Variables**, and add:
   ```
   SPRING_PROFILES_ACTIVE=prod
   DB_URL=jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?connectionTimeZone=UTC
   DB_USERNAME=${{MySQL.MYSQLUSER}}
   DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}
   JWT_SECRET=<generated secret>
   CORS_ALLOWED_ORIGINS=https://<service>.up.railway.app
   ```
4. **Settings → Networking → Generate Domain** (this gives the `*.up.railway.app` HTTPS URL), then set **Healthcheck Path** to `/actuator/health`.
5. Deploy. Flyway creates the schema on first boot.

With the Railway CLI and a token (`RAILWAY_TOKEN`), the same steps can be scripted: `railway up`, `railway variables --set KEY=value`.

## Option 2: Render (app) with Aiven (free MySQL)

1. **Database:** at <https://console.aiven.io> create a service → **MySQL** → **Free plan**. When it is running, copy the connection details from *Overview*: host, port, user (`avnadmin`), password and database (`defaultdb`).
2. **App:** at <https://dashboard.render.com> choose **New → Blueprint** and connect this repository. Render reads `render.yaml`, creates the free Docker web service and **generates `JWT_SECRET` itself**.
3. When prompted, fill in:
   ```
   DB_URL=jdbc:mysql://<host>:<port>/defaultdb?sslMode=REQUIRED&connectionTimeZone=UTC
   DB_USERNAME=avnadmin
   DB_PASSWORD=<aiven password>
   ```
4. Deploy. Render serves HTTPS at `https://swapp-xxxx.onrender.com`. CORS defaults to that URL (`RENDER_EXTERNAL_URL`), so there is nothing else to set.

Note: free Render instances sleep when idle, so the first request after a pause takes a while.

## Option 3: AWS EC2 with Amazon RDS

1. **RDS:** create a MySQL 8.4 instance (db.t4g.micro), not publicly accessible, in the same VPC as the EC2 instance. Its security group should allow port 3306 only from the EC2 security group. Create a `swapp` database.
2. **EC2:** launch Amazon Linux 2023 (t3.small or larger), allow ports 80/443, and install Docker:
   ```bash
   sudo dnf install -y docker && sudo systemctl enable --now docker
   ```
3. Build or pull the image and run it:
   ```bash
   docker build -t swapp .
   docker run -d --name swapp --restart unless-stopped -p 127.0.0.1:8080:8080 --env-file /etc/swapp.env swapp
   ```
   `/etc/swapp.env` holds the variables above (`chmod 600`).
4. **HTTPS:** put Caddy in front of the app. It obtains and renews Let's Encrypt certificates automatically:
   ```
   # /etc/caddy/Caddyfile
   swapp.example.com {
       reverse_proxy 127.0.0.1:8080
   }
   ```
   Alternatively, use an Application Load Balancer with an ACM certificate.
5. Logs: `docker logs -f swapp` (or ship them to CloudWatch with the awslogs driver).

## Post-deploy checklist

- `GET https://<domain>/actuator/health` returns `{"status":"UP"}`.
- Open the site in a private window, register two accounts, list an item, make, accept and complete an offer.
- Or run the automated browser journey against the live URL:
  ```bash
  cd e2e && npm install && node journey.mjs https://<domain> ./shots
  ```
- Confirm the `swapp_token` cookie is flagged `Secure; HttpOnly; SameSite=Strict`.
