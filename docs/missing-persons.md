# TTS vermissingen — API en database

Eigen feature voor [tailormap-ideas #20](https://github.com/The-Tracking-Solution/tailormap-ideas/issues/20), 22 november 2026. Zie het uitvoerige bouwplan `docs/missing-persons.md` in de bijbehorende viewer-featurebranch.

Standaard uitgeschakeld. De API gebruikt bestaande applicatieautorisatie en sessie/CSRF; daarnaast vereist iedere operatie lidmaatschap van een expliciet geconfigureerde organisatie die toegang tot de casus heeft. Applicatie-ID is onderdeel van iedere gegevensselectie. Organisatie-ID's uit requestbodies leveren op zichzelf geen leesrecht op.

## Installatievoorbereiding

1. Gebruik eerst een geïsoleerde, schrijfbare DEV-geodataomgeving met PostGIS in schema `public`. De module maakt geen database of PostGIS-extensie aan. Gebruik een specifieke databaserol voor dit schema; geen algemene GeoServer-leesrol of replica.
2. Controleer dat schema `vermissingen` nieuw is of exact deze eigen Flyway-historie heeft. Onbekende bestaande tabellen worden niet automatisch als V1 geadopteerd. Maak een databaseback-up vóór de eerste migratie.
3. Neem `docs/missing-persons.properties.example` over in de bestaande beveiligde runtimeconfiguratie. Vul de JDBC-verbinding via omgeving/secrets in en vervang de voorbeeldgroepen door bestaande Tailormap-groepen. Er is geen automatische import van account-/organisatiedata.
4. Stel de API-optie expliciet op `true`. De afzonderlijke Hikari-pool voert alleen `classpath:db/tts-missing` uit, met eigen historie in `vermissingen`, `cleanDisabled=true`, geen automatische baseline. De gewone Tailormap-datasource wordt niet vervangen.
5. Schakel in de applicatie de component `missing-persons` in. Beide schakelaars zijn vereist. Geen wijzigingen aan draaiende systemen zijn in deze feature uitgevoerd.

De bevestigde eerste bewaartermijn is ten minste één kalenderjaar. `statistics-retention-days=365` is de eerste instelling; langere termijnen kunnen tot 3650 dagen. De berekening neemt het maximum van één kalenderjaar en het aantal ingestelde dagen. `retain-exact-locations=true` bewaart de gevraagde vermist-/vindlocaties, uitsluitend in afgeschermde statistiek. Het is geen anonieme openbare dataset.

## Tabellen

| Tabel | Inhoud | Bij afsluiten |
|---|---|---|
| `active_cases` | Actieve casus, locatie/tijd, snelheid, private intake | Verwijderen |
| `case_organisations` | Geselecteerde samenwerkende organisaties | Cascade verwijderen |
| `photos` | Eén opnieuw gecodeerde JPEG per casus | Cascade verwijderen |
| `events` | Private tijdelijke tijdlijn | Cascade verwijderen |
| `closed_stats` | Nieuw statistiek-ID, gestructureerde velden, exacte locaties indien ingesteld | Bewaren tot `expires_at` |

Er is geen gewone persoonsdata-archieftabel en geen FK van statistiek naar het actieve dossier. De transactie gebruikt uitsluitend de geodataverbinding, inclusief foto. Een mislukte stap laat de actieve casus intact; de API meldt pas privacy-afgerond na de commit. De uurlijkse opruimtaak verwijdert verlopen statistiek; leesoperaties filteren verlopen records ook vóór die taak.

## Contract

Basis: `/api/app/{viewerName}/missing-persons`. De viewer heeft intern samengestelde ID's zoals `app/test`; de client verwijdert dit `app/`-voorvoegsel voordat hij `{viewerName}` encodeert.

| Methode/pad | Doel |
|---|---|
| GET `/configuration` | Organisaties, eigen lidmaatschap, bewaartermijn |
| GET/POST `/cases` | Zichtbare actieve casussen / aanmaken |
| GET/PUT `/cases/{id}` | Ophalen / bijwerken met `version` |
| POST `/cases/{id}/events` | Tijdlijnupdate met versie, soort en tekst |
| GET `/cases/{id}/search-area` | Geodetische indicatieve buffer met peiltijd en straal |
| GET/POST `/cases/{id}/photo` | Beveiligde foto / JPEG- of PNG-upload |
| POST `/cases/{id}/close` | Gecontroleerde, atomaire afsluiting |
| GET `/statistics` | Afgeschermde, niet-verlopen statistiek; maximaal 500 recente records |

Alle leesresponsen gebruiken `no-store`. Foto-upload: multipartveld `photo`, requestparameter `version`. Voor JSON-mutaties staat `version` in de body. De module maakt geen CSRF-uitzonderingen. `404` onthult geen casus van een andere organisatie/applicatie; `409` betekent dat de casus intussen is gewijzigd.

Nieuwe casussen hebben een eigenaar uit het eigen organisatie-lidmaatschap en minimaal die organisatie als deelnemer. Bekende andere organisaties kunnen worden toegevoegd. Gedeelde gebruikers mogen alle casusgegevens aanpassen en afsluiten; alleen een eigenorganisatie-lid wijzigt delen. Eigendom overdragen is in deze versie niet beschikbaar.

Foto's: maximale upload 10 MB, maximale afmetingen 16 megapixels, JPEG/PNG-decodering, metadata verwijderen door opnieuw tekenen/coderen, maximaal 1600 pixels. Geen originele bestandsnamen, thumbnails of losse bestanden. SVG/GIF/HEIC worden niet geaccepteerd.

## Operationele grenzen

- Geen migratie uit de oude `vermissing_punt`-tabellen zonder gecontroleerde bron-DDL en tijdsinterpretatie.
- Weer-/watermetingen nog niet aangesloten; statistiek meldt `unknown`. Afsluiten werkt onafhankelijk daarvan.
- Geen publieke geolaag of externe locatieverzoeken. Geef GeoServer-/algemene bronrollen geen toegang tot dit schema.
- Productie→test-refresh moet dit schema expliciet behandelen. Geen productiecasussen overzetten en geen lokale moduledata overschrijven. Deze feature wijzigt de bestaande refreshrunner nog niet.
- Zorg dat requestbody-/SQL-parameterlogging niet wordt geactiveerd voor deze gegevens. Back-ups, WAL, replica's en herstelkopieën blijven onder afzonderlijk retentie-/herstelbeleid; primaire DELETE is geen onmiddellijke verwijdering uit back-ups.

## Testen

Gebruik Java 25 en de bestaande Maven-opzet. `mvn -Dtest='Missing*Test' -Ddocker.skip=true -DskipQA=true -DskipJavaDoc=true test` voert de nieuwe unitproeven uit. De workflowproef vereist expliciet:

```sh
TTS_MISSING_TEST_URL=jdbc:postgresql://127.0.0.1:55432/missing_test mvn -Dtest='Missing*Test' -Ddocker.skip=true -DskipQA=true -DskipJavaDoc=true test
```

De proef accepteert uitsluitend deze lokale testdatabase, gebruiker `postgres`, leeg wachtwoord. Dit is een wegwerpfixture, geen runtimeconfiguratie. Hij maakt echte casussen/foto's, test organisatie- en applicatiegrenzen, ingetrokken toegang, versieconflicten, cirkelberekening, terugdraaien bij fout, dubbele afsluiting en automatische vervalverwijdering. Gebruik geen echte personen.
