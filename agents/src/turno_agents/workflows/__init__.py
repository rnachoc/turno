"""Durable workflows: they wait for days if needed, survive restarts, and decide nothing.

Every decision comes from the core, which is asked again at each step. See docs/adr/0012.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""
