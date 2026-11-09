import http from 'node:http';
import { readFile } from 'node:fs/promises';

const args = process.argv.slice(2);
const portFlag = args.indexOf('--port');
const hostFlag = args.indexOf('--host');
const port = Number(portFlag >= 0 ? args[portFlag + 1] : 7100);
const host = hostFlag >= 0 && args[hostFlag + 1] && !args[hostFlag + 1].startsWith('--') ? args[hostFlag + 1] : '0.0.0.0';

const server = http.createServer(async (request, response) => {
  try {
    const html = await readFile(new URL('./index.html', import.meta.url));
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(html);
  } catch (error) {
    response.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Prototype could not be loaded.');
  }
});
server.listen(port, host, () => console.log(`Nikon prototype: http://localhost:${port}/`));
