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

### 3. Email: Brevo (free, 300 emails a day)

Password reset and email verification need real email delivery. Any SMTP provider works; Brevo's free plan is enough for a small community.

1. Sign up at <https://www.brevo.com> (free plan).
2. **Senders, domains and dedicated IPs → Senders:** add and verify the address emails should come from (for example `no-reply@yourdomain.com`, or your own Gmail address while testing). Authenticating a domain you own (SPF and DKIM records) greatly improves inbox placement.
3. **SMTP and API → SMTP:** generate an SMTP key. Note the SMTP login shown on that page.
4. In the Render service's **Environment**, set:
   ```
   MAIL_PROVIDER=smtp
   MAIL_FROM=<the verified sender>
   SMTP_HOST=smtp-relay.brevo.com
   SMTP_USERNAME=<SMTP login>
   SMTP_PASSWORD=<SMTP key>
   CONTACT_EMAIL=<public contact for privacy questions>
   ```
5. Save. Render redeploys. Use **Forgot password** on the live site to check that the email arrives.
6. Once it does, set `REQUIRE_EMAIL_VERIFICATION=true`, so new members must confirm their address before they can post, make offers or send messages.

### 4. Sign in with Google (free, optional)

1. Open <https://console.cloud.google.com>, create a project (for example "Swapp").
2. **APIs and Services → OAuth consent screen** (called "Google Auth Platform" in newer consoles): choose **External**, fill in the app name, support email and the developer contact, and add the scopes `openid`, `email` and `profile` (all non-sensitive, so Google needs no verification review). Add links to `/privacy.html` and `/terms.html` on the live site. Then **Publish** the app; while it is in "Testing", only the test users you list can sign in.
3. **APIs and Services → Credentials → Create credentials → OAuth client ID**, type **Web application**. Under **Authorised redirect URIs** add:
   ```
   http://localhost:8080/login/oauth2/code/google
   https://<your-service>.onrender.com/login/oauth2/code/google
   ```
   (and the same path on a custom domain later). No JavaScript origins are needed: the whole flow runs on the server.
4. Copy the client ID and secret into the Render service's **Environment** as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` (never into the repository). For local testing, put them in your own `.env` or shell.
5. Save. After the redeploy, the sign-in and sign-up pages show **Continue with Google**. Try it with a new Google account (you should land on "Almost there" to accept the Terms) and with the email of an existing account (it should sign you in and email a security alert).

Without the two variables the button is hidden and the feature is off.

Until SMTP is configured, the app runs with `MAIL_PROVIDER=log`. Emails are then only written to the logs (and a warning is logged at startup), so nobody receives them. The app refuses to start with `REQUIRE_EMAIL_VERIFICATION=true` unless `MAIL_PROVIDER=smtp`, because new members could never verify.

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
- Check that the `swapp_token` cookie is `Secure; HttpOnly; SameSite=Strict`, and that responses carry `Strict-Transport-Security` (this confirms the app sees the platform's `X-Forwarded-Proto`).
- Abuse limits are on by default (see `docs/ARCHITECTURE.md`). They count per instance, so they stay correct only while the app runs as one instance, which is the case on every option above.
- Backups: Neon's free plan keeps a short point-in-time restore window. For anything beyond a demo, schedule a `pg_dump` too.

"Near me" uses the browser Geolocation API, which only works on HTTPS pages (and on `localhost`). Every option above serves HTTPS.
