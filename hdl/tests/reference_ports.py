"""Port-only adapters for independent handwritten/reference differential tests."""
import re
from pathlib import Path


def snake(name):
    overrides = {'usesV1': 'uses_v1', 'usesV2': 'uses_v2',
                 'usesVOld': 'uses_v_old', 'usesVMask': 'uses_v_mask',
                 'usesRs1': 'uses_rs1', 'usesRs2': 'uses_rs2',
                 'vsrc1': 'vsrc1', 'vsrc2': 'vsrc2'}
    return overrides.get(name, re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower())


def payload(name):
    return '.'.join(snake(part) for part in name.split('_'))


def reference_block(path, module, resolver):
    header = Path(path).read_text().split(');', 1)[0].split('(', 1)[1]
    declarations, connections, checks = [], [], []
    direction, width = None, ''
    for chunk in header.split(','):
        match = re.fullmatch(r'(?:(input|output)\s+)?(?:(\[[^]]+\])\s+)?(\w+)', chunk.strip())
        if not match:
            raise ValueError(f'Unparsed port: {chunk}')
        port_direction, port_width, name = match.groups()
        direction = port_direction or direction
        width = port_width or ('' if port_direction else width)
        expression, guard = resolver(name)
        if direction == 'output':
            ref_name = 'ref_' + name
            declarations.append(f'logic {width} {ref_name};')
            connections.append(f'.{name}({ref_name})')
            checks.append(f'if ({guard}) assert ({expression} === {ref_name}) '
                          f'else $fatal(1, "cycle=%0d {name} dut=%h ref=%h", '
                          f'cycle, {expression}, {ref_name});')
        elif direction == 'input':
            connections.append(f'.{name}({expression})')
        else:
            raise ValueError(name)
    return ('\n'.join(declarations) + '\n' + module + ' i_reference(\n'
            + ',\n'.join(connections) + '\n);', '\n'.join(checks))
