package crowdwatch.ds;

/**
 * Undirected weighted graph stored as adjacency lists, with Dijkstra's shortest-path algorithm.
 * <p>
 * Nodes are areas (identified by a string ID) and edges are walking links weighted in
 * minutes. An AVL tree maps each ID to its array index, so looking up a node is O(log V).
 * Space is O(V + E).
 */
public class Graph {
    /** Distance used for "unreachable". Divided by 4 so that adding two distances cannot overflow. */
    public static final int INF = Integer.MAX_VALUE / 4;

    /** One end of a link in an adjacency list: the neighbour's index and the walk time. */
    public static final class Edge {
        public final int to;
        public final int weight;
        Edge(int to, int weight) { this.to = to; this.weight = weight; }
    }

    /**
     * Edge-cost function for Dijkstra. Lets the caller change the weight of an edge, for example
     * to make crowded areas expensive or restricted areas impassable (return {@link Graph#INF}).
     */
    public interface Cost { int of(int from, int to, int weight); }

    /**
     * Result of a Dijkstra run from one source: shortest distance and previous node for every
     * node, plus the order in which nodes were settled (shown in the GUI).
     */
    public static final class Paths {
        public final int source;
        public final int[] dist;
        public final int[] prev;
        public final LinkedList<String> visitOrder = new LinkedList<>();
        private final Graph g;

        /** Creates a result with every distance set to {@link Graph#INF} and no previous nodes. */
        Paths(Graph g, int source, int n) {
            this.g = g;
            this.source = source;
            dist = new int[n];
            prev = new int[n];
            for (int i = 0; i < n; i++) { dist[i] = INF; prev[i] = -1; }
        }

        /**
         * @param id target node ID
         * @return shortest distance from the source, or {@link Graph#INF} if unreachable or unknown
         */
        public int distanceTo(String id) {
            int i = g.indexOf(id);
            return i < 0 ? INF : dist[i];
        }

        /**
         * Rebuilds the shortest path by following the {@code prev} links back from the target.
         *
         * @param id target node ID
         * @return node IDs from source to target, or an empty list if there is no path
         */
        public LinkedList<String> pathTo(String id) {
            LinkedList<String> path = new LinkedList<>();
            int i = g.indexOf(id);
            if (i < 0 || dist[i] >= INF) return path;
            for (int v = i; v != -1; v = prev[v]) path.addFirst(g.name(v));
            return path;
        }
    }

    // node ID -> array index, O(log V) lookup
    private final AVLTree<String, Integer> index = new AVLTree<>();
    // array index -> node ID; grows by doubling
    private String[] names = new String[16];
    // adjacency list for each node
    private LinkedList<Edge>[] adj = newAdj(16);
    // number of nodes
    private int n;

    /** Creates an array of adjacency lists (generic arrays need an unchecked cast in Java). */
    @SuppressWarnings("unchecked")
    private static LinkedList<Edge>[] newAdj(int cap) { return (LinkedList<Edge>[]) new LinkedList[cap]; }

    /**
     * Adds a node, or returns the existing index if the ID is already present.
     * The arrays double in size when full, so adding is amortised O(log V) (because of the index lookup).
     *
     * @return the node's index
     */
    public int addNode(String id) {
        Integer existing = index.search(id);
        if (existing != null) return existing;
        if (n == names.length) {
            String[] nn = new String[n * 2];
            LinkedList<Edge>[] na = newAdj(n * 2);
            for (int i = 0; i < n; i++) { nn[i] = names[i]; na[i] = adj[i]; }
            names = nn;
            adj = na;
        }
        names[n] = id;
        adj[n] = new LinkedList<>();
        index.insert(id, n);
        return n++;
    }

    /**
     * Adds a two-way link between two existing nodes.
     *
     * @param weight walk time in minutes, must be positive
     * @return false if a node is unknown, the nodes are the same, the weight is not positive,
     *         or the link already exists
     */
    public boolean connect(String a, String b, int weight) {
        int i = indexOf(a), j = indexOf(b);
        if (i < 0 || j < 0 || i == j || weight <= 0) return false;
        for (Edge e : adj[i]) if (e.to == j) return false;
        adj[i].addLast(new Edge(j, weight));
        adj[j].addLast(new Edge(i, weight));
        return true;
    }

    /** @return the array index of a node, or -1 if the ID is unknown */
    public int indexOf(String id) {
        Integer i = id == null ? null : index.search(id);
        return i == null ? -1 : i;
    }

    /** @return the ID of the node at index i */
    public String name(int i) { return names[i]; }

    /** @return number of nodes */
    public int size() { return n; }

    /** @return the adjacency list of node i */
    public LinkedList<Edge> neighbours(int i) { return adj[i]; }

    /** @return number of links (each link is stored twice, once at each end) */
    public int edgeCount() {
        int c = 0;
        for (int i = 0; i < n; i++) c += adj[i].size();
        return c / 2;
    }

    /** Removes all nodes and links. */
    public void clear() {
        index.clear();
        names = new String[16];
        adj = newAdj(16);
        n = 0;
    }

    /** Dijkstra using the plain walk times as edge costs. */
    public Paths shortestPaths(String from) { return shortestPaths(from, (u, v, w) -> w); }

    /**
     * Dijkstra's algorithm from one source using a min-heap. O((V + E) log V).
     * <p>
     * The heap holds {node, distance} pairs. A node can be in the heap more than once (we insert
     * again instead of decreasing a key); stale entries are skipped with the {@code done} array.
     * Edges whose cost is {@link #INF} are never used.
     *
     * @param from source node ID
     * @param cost edge-cost function
     * @return distances and previous-node links for every node
     */
    public Paths shortestPaths(String from, Cost cost) {
        Paths p = new Paths(this, indexOf(from), n);
        if (p.source < 0) return p;
        p.dist[p.source] = 0;
        MinHeap<int[]> heap = new MinHeap<>((x, y) -> Integer.compare(x[1], y[1]));
        heap.insert(new int[] { p.source, 0 });
        boolean[] done = new boolean[n];
        while (!heap.isEmpty()) {
            int[] top = heap.poll();
            int u = top[0];
            // stale entry: u was already settled with a shorter distance
            if (done[u]) continue;
            done[u] = true;
            p.visitOrder.addLast(names[u] + " (" + p.dist[u] + ")");
            for (Edge e : adj[u]) {
                // relax edge u -> e.to: keep the new distance if it is shorter
                int c = cost.of(u, e.to, e.weight);
                if (c >= INF || done[e.to]) continue;
                int d = p.dist[u] + c;
                if (d < p.dist[e.to]) {
                    p.dist[e.to] = d;
                    p.prev[e.to] = u;
                    heap.insert(new int[] { e.to, d });
                }
            }
        }
        return p;
    }

    /** @return every node with its neighbours and walk times, one node per line */
    public String adjacencyText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(names[i]).append(" ->");
            for (Edge e : adj[i]) sb.append(' ').append(names[e.to]).append('(').append(e.weight).append(')');
            sb.append('\n');
        }
        return sb.toString();
    }
}
