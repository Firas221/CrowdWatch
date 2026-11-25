"""Generates big.venue: a 20 x 20 grid of areas (400), 760 links, 250 units, 8 exits, automation on."""
import sys, random
random.seed(7)
N = 20
L = ["venue: Stress Test Mega Venue", "code: mega", "thresholds: 60 80 95",
     "auto: CROWD HIGH trend", "supervision: 15", "ack: 2"]
ids = [[f"A{r:02d}-{c:02d}" for c in range(N)] for r in range(N)]
exits = [ids[0][0], ids[0][N-1], ids[N-1][0], ids[N-1][N-1], ids[0][N//2], ids[N-1][N//2], ids[N//2][0], ids[N//2][N-1]]
L.append("exit: " + " ".join(exits))
for r in range(N):
    for c in range(N):
        L.append(f"area: {ids[r][c]} | Area {r}-{c} | Sector {r//5 * 2 + c//10 + 1} | HALL | {random.randint(1000, 5000)}")
for r in range(N):
    for c in range(N):
        if c+1 < N: L.append(f"link: {ids[r][c]} {ids[r][c+1]} {random.randint(1,4)}")
        if r+1 < N: L.append(f"link: {ids[r][c]} {ids[r+1][c]} {random.randint(1,4)}")
L += ["category: LOST_PERSON | Lost person | CRITICAL | lost-person | personal",
      "category: MEDICAL | Medical | CRITICAL | first-aid",
      "category: CROWD | Crowd pressure | HIGH | crowd-control",
      "category: WHEELCHAIR | Wheelchair | NORMAL | wheelchair",
      "category: INFO | Information | LOW |"]
skills = ["lost-person", "first-aid", "crowd-control", "wheelchair"]
for u in range(250):
    r, c = random.randrange(N), random.randrange(N)
    sk = random.sample(skills, 2)
    L.append(f"unit: U{u:03d} | Team {u} | {ids[r][c]} | Arabic, English, {sk[0]}, {sk[1]}")
open(sys.argv[1] if len(sys.argv) > 1 else "big.venue", "w").write("\n".join(L) + "\n")
