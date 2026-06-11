import { useEffect, useState } from "react";
import "./styles.css";

// Échelle des niveaux : couleur + libellés cohérents partout
const TIER_COLOR = {
  wtf: "var(--t-wtf)",
  "1000": "var(--t-1000)",
  "750": "var(--t-750)",
  "500": "var(--t-500)",
  "300": "var(--t-300)",
};
const TIER_LABEL = {
  wtf: "World Tour Finals",
  "1000": "Super 1000",
  "750": "Super 750",
  "500": "Super 500",
  "300": "Super 300",
};
const TIER_SHORT = { wtf: "WTF", "1000": "S1000", "750": "S750", "500": "S500", "300": "S300" };
const ALL_TIERS = ["wtf", "1000", "750", "500", "300"];

function toneClass(tone) {
  if (tone === "win") return "res-win";
  if (tone === "out") return "res-out";
  return "";
}

// Statut français à trois états : true = présents (tricolore), false = aucun
// (confirmé, gris), null/undefined = inconnu (distinct, pas un « aucun » confirmé).
function frBannerClass(present) {
  if (present === true) return "";
  if (present === false) return " none";
  return " unknown";
}

export default function App() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [active, setActive] = useState(new Set(ALL_TIERS));
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      fetch("/data.json", { cache: "no-store" })
        .then((r) => {
          if (!r.ok) throw new Error("data.json introuvable");
          return r.json();
        })
        .then((d) => {
          if (!cancelled) {
            setData(d);
            setError(null);
          }
        })
        .catch((e) => {
          if (!cancelled) setError(e.message);
        });
    load();
    // Auto-rafraîchissement : le collecteur publie plusieurs fois par jour, la
    // page suit sans rechargement. Si un refresh échoue, on garde les dernières
    // données affichées (l'erreur ne s'affiche que si on n'a encore rien reçu).
    const id = setInterval(() => {
      setNow(Date.now()); // recalcule aussi la fraîcheur du badge
      load();
    }, 15 * 60 * 1000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  function toggle(tier) {
    setActive((prev) => {
      const next = new Set(prev);
      next.has(tier) ? next.delete(tier) : next.add(tier);
      return next;
    });
  }

  if (error && !data) {
    return (
      <div className="vbwf">
        <p className="empty">Impossible de charger les données ({error}). Le collecteur n'a peut-être pas encore publié de data.json.</p>
      </div>
    );
  }
  if (!data) {
    return (
      <div className="vbwf">
        <p className="empty">Chargement…</p>
      </div>
    );
  }

  // Replis défensifs : un data.json ancien ou partiel ne doit pas blanchir la
  // page (échec gracieux, cf. CLAUDE.md) — chaque section absente s'affiche vide.
  const current = data.current ?? [];
  const upcoming = data.upcoming ?? [];
  const players = data.players ?? [];

  const visibleCurrent = current.filter((t) => active.has(t.tier));
  const visibleUpcoming = upcoming.filter((t) => active.has(t.tier));
  const stamp = new Date(data.generatedAt).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" });
  // Badge honnête : les données viennent d'un cron (pas du direct). Au-delà de
  // 12 h sans rafraîchissement, on signale que le collecteur n'a pas publié.
  const ageHours = (now - new Date(data.generatedAt).getTime()) / 3600000;
  const fresh = Number.isFinite(ageHours) && ageHours < 12;

  return (
    <div className="vbwf">
      <header className="vbwf__head">
        <svg className="vbwf__arc" viewBox="0 0 320 180" fill="none" aria-hidden="true">
          <path d="M10 170 Q 150 -40 310 90" stroke="#2b3340" strokeWidth="1.5" strokeDasharray="2 6" />
          <g transform="translate(305,86)">
            <circle r="3.5" fill="#e8b24a" />
            <path d="M0 0 l10 -7 M0 0 l11 -1 M0 0 l9 6" stroke="#e8b24a" strokeWidth="1.4" />
          </g>
        </svg>
        <div className="vbwf__eyebrow">
          <span>{data.weekLabel}</span>
          <span className="vbwf__live"><i /> {fresh ? "À jour" : "Données anciennes"}</span>
        </div>
        <h1 className="vbwf__title">Veille <b>BWF</b> World Tour</h1>
        <p className="vbwf__sub">
          Tournois internationaux des 5 niveaux du World Tour, priorité à la semaine en cours, puis aux Français en lice.
        </p>

        <div className="vbwf__filters">
          {ALL_TIERS.map((tier) => (
            <button
              key={tier}
              className="chip"
              aria-pressed={active.has(tier)}
              onClick={() => toggle(tier)}
            >
              <i style={{ background: TIER_COLOR[tier] }} />
              {TIER_LABEL[tier]}
            </button>
          ))}
        </div>
      </header>

      <div className="vbwf__grid">
        <div className="vbwf__main">
        <section>
          <h2 className="sec-label">En cours cette semaine</h2>

          {visibleCurrent.length === 0 && (
            <div className="card empty">Aucun tournoi de ce niveau en cours cette semaine.</div>
          )}

          {visibleCurrent.map((t) => (
            <div className="card tourn" key={t.name}>
              <div className="tourn__top">
                <span className="tier" style={{ background: TIER_COLOR[t.tier] }}>{TIER_LABEL[t.tier]}</span>
                <span className="tourn__day">{t.dayLabel}</span>
              </div>
              <div className="tourn__name">{t.name}</div>
              <div className="tourn__meta">
                <span>{t.location}</span>
                <span>{t.dates}</span>
                <span>Dotation <b>{t.prize}</b></span>
                <span>Fuseau <b>{t.timezone}</b></span>
              </div>

              {t.seeds?.length > 0 && (
                <div className="seeds">
                  <h4>Têtes d'affiche — simple messieurs / dames</h4>
                  <ul>
                    {t.seeds.map((s, k) => (
                      <li key={k}><span className="s">{s.rank}</span> {s.name}</li>
                    ))}
                  </ul>
                </div>
              )}

              {t.frenchStatus && (
                <div className={"fr-banner" + frBannerClass(t.frenchStatus.present)}>
                  <h5>
                    {/* == null volontaire : null OU undefined = statut inconnu */}
                    {t.frenchStatus.present == null ? "❔" : "🇫🇷"} {t.frenchStatus.title}
                    {t.frenchStatus.confirm && <span className="tag-confirm">à confirmer</span>}
                  </h5>
                  <p>{t.frenchStatus.note}</p>
                </div>
              )}
            </div>
          ))}
        </section>

        <section className="vbwf__upcoming">
          <h2 className="sec-label">À venir — prochains tournois World Tour</h2>
          {visibleUpcoming.length === 0 && (
            <div className="card empty">Aucun tournoi de ce niveau au calendrier.</div>
          )}
          <div className="upcoming">
            {visibleUpcoming.map((u) => (
              <div className="up" key={u.name}>
                <div className="up__date">{u.dates}</div>
                <div className="up__name">
                  {u.name}
                  <span className="tier" style={{ background: TIER_COLOR[u.tier], fontSize: 10, padding: "2px 7px" }}>
                    {TIER_SHORT[u.tier]}
                  </span>
                </div>
                <div className="up__fr">{u.french}</div>
              </div>
            ))}
          </div>
        </section>
        </div>

        <aside>
          <h2 className="sec-label">Vos Français</h2>
          {players.length === 0 && (
            <div className="card empty">Aucun résultat récent des Bleus dans le fil equipe-france.</div>
          )}
          <div className="players">
            {players.map((p) => (
              <div className="player" key={p.name}>
                <div className="player__name">
                  <span>{p.name}</span>
                  {p.rank && <span className="player__rank">{p.rank}</span>}
                </div>
                {p.lines.map((l, k) => {
                  // Affichage structuré : tournoi en gras, date discrète, stade
                  // coloré par tone. Repli sur value si un sous-champ manque.
                  const structured = l.tournament && l.stage;
                  return (
                    <div className="player__row" key={k}>
                      <b>{l.label} :</b>
                      {structured ? (
                        <>
                          <span className="player__tour">{l.tournament}</span>
                          {l.date && <span className="player__date">{l.date}</span>}
                          <span className={toneClass(l.tone)}>{l.stage}</span>
                        </>
                      ) : (
                        <span className={toneClass(l.tone)}>{l.value}</span>
                      )}
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
        </aside>
      </div>

      <footer className="vbwf__foot">
        Données rafraîchies par le collecteur — dernière mise à jour : <b className="stamp">{stamp}</b>.<br />
        Sources : calendrier BWF World Tour (corporate.bwfbadminton.com) et suivi des Bleus via equipe-france.fr / FFBaD.
        Résultats au grain « tour » (pas de score live). Les mentions « à confirmer » indiquent le niveau de confiance de la donnée.
      </footer>
    </div>
  );
}
